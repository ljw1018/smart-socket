package org.smartboot.socket.protocol.http.process;

import com.sun.org.apache.xml.internal.utils.ObjectPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.smartboot.socket.MessageProcessor;
import org.smartboot.socket.protocol.http.HttpEntity;
import org.smartboot.socket.protocol.http.accesslog.AccessLogger;
import org.smartboot.socket.protocol.http.jndi.JndiManager;
import org.smartboot.socket.protocol.http.jndi.resources.DataSourceConfig;
import org.smartboot.socket.protocol.http.servlet.core.ClientSocketException;
import org.smartboot.socket.protocol.http.servlet.core.HostConfiguration;
import org.smartboot.socket.protocol.http.servlet.core.HostGroup;
import org.smartboot.socket.protocol.http.servlet.core.SimpleRequestDispatcher;
import org.smartboot.socket.protocol.http.servlet.core.WebAppConfiguration;
import org.smartboot.socket.protocol.http.servlet.core.WinstoneConstant;
import org.smartboot.socket.protocol.http.servlet.core.WinstoneRequest;
import org.smartboot.socket.protocol.http.servlet.core.WinstoneResponse;
import org.smartboot.socket.protocol.http.util.MapConverter;
import org.smartboot.socket.protocol.http.util.StringUtils;
import org.smartboot.socket.transport.AioSession;
import org.smartboot.socket.util.StateMachineEnum;

import javax.servlet.ServletException;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务器消息处理器,由服务器启动时构造
 *
 * @author 三刀
 */
public final class HttpServerMessageProcessor implements MessageProcessor<HttpEntity> {
    private static final Logger LOGGER = LogManager.getLogger(HttpServerMessageProcessor.class);
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private HostGroup hostGroup;
    private JndiManager globalJndiManager = null;
    private final Map<String, String> args;
    public HttpServerMessageProcessor(final Map<String, String> args) {
        this.args=args;
        try {
            initializeJndi();
            hostGroup=new HostGroup(new ObjectPool(),globalJndiManager,args);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * Instantiate Jndi Manager if needed.
     */
    private void initializeJndi() {
        // If jndi is enabled, run the container wide jndi populator
        if (StringUtils.booleanArg(args, "useJNDI", Boolean.FALSE)) {
            // Set jndi resource handler if not set (workaround for JamVM bug)
            try {
                final Class<?> ctxFactoryClass = Class.forName("net.winstone.jndi.url.java.javaURLContextFactory");
                if (System.getProperty("java.naming.factory.initial") == null) {
                    System.setProperty("java.naming.factory.initial", ctxFactoryClass.getName());
                }
                if (System.getProperty("java.naming.factory.url.pkgs") == null) {
                    System.setProperty("java.naming.factory.url.pkgs", "net.winstone.jndi");
                }
            } catch (final ClassNotFoundException err) {
                LOGGER.error("JNDI Error ", err);
            }
            // instanciate Jndi Manager
            final String jndiMgrClassName = StringUtils.stringArg(args, "containerJndiClassName", JndiManager.class.getName()).trim();
            try {
                // Build the realm
                final Class<?> jndiMgrClass = Class.forName(jndiMgrClassName);
                globalJndiManager = (JndiManager) jndiMgrClass.newInstance();
                globalJndiManager.initialize();
                LOGGER.info("JNDI Started {}", jndiMgrClass.getName());
            } catch (final ClassNotFoundException err) {
                LOGGER.error("JNDI disabled at container level - can't find JNDI Manager class", err);
            } catch (final Throwable err) {
                LOGGER.error("JNDI disabled at container level - couldn't load JNDI Manager: " + jndiMgrClassName, err);
            }
            // instanciate data
            final Collection<String> keys = new ArrayList<String>(args != null ? args.keySet() : (Collection<String>) new ArrayList<String>());
            for (final Iterator<String> i = keys.iterator(); i.hasNext();) {
                final String key = i.next();
                if (key.startsWith("jndi.resource.")) {
                    final String resourceName = key.substring(14);
                    final String className = args.get(key);
                    final String value = args.get("jndi.param." + resourceName + ".value");
                    LOGGER.debug("Creating object: {} from startup arguments", resourceName);
                    createObject(resourceName.trim(), className.trim(), value, args);
                }
            }

        }
    }
    protected final boolean createObject(final String name, final String className, final String value, final Map<String, String> args) {
        // basic check
        if ((className == null) || (name == null)) {
            return Boolean.FALSE;
        }

        // If we are working with a datasource
        if (className.equals("javax.sql.DataSource")) {
            try {
                final DataSourceConfig dataSourceConfig = MapConverter.apply(extractRelevantArgs(args, name), new DataSourceConfig());
                globalJndiManager.bind(dataSourceConfig);
                return Boolean.TRUE;
            } catch (final Throwable err) {
                LOGGER.error("Error building JDBC Datasource object " + name, err);
            }
        } // If we are working with a mail session
        else if (className.equals("javax.mail.Session")) {
            try {
                final Properties p = new Properties();
                p.putAll(extractRelevantArgs(args, name));
                globalJndiManager.bindSmtpSession(name, p, Thread.currentThread().getContextClassLoader());
                return Boolean.TRUE;
            } catch (final Throwable err) {
                LOGGER.error("Error building JavaMail session " + name, err);
            }
        } // If unknown type, try to instantiate with the string constructor
        else if (value != null) {
            try {
                globalJndiManager.bind(name, className, value, Thread.currentThread().getContextClassLoader());
                return Boolean.TRUE;
            } catch (final Throwable err) {
                LOGGER.error("Error building JNDI object " + name + " (class: " + className + ")", err);
            }
        }

        return Boolean.FALSE;
    }
    private Map<String, String> extractRelevantArgs(final Map<String, String> input, final String name) {
        final Map<String, String> relevantArgs = new HashMap<String, String>();
        for (final Iterator<String> i = input.keySet().iterator(); i.hasNext();) {
            final String key = i.next();
            if (key.startsWith("jndi.param." + name + ".")) {
                relevantArgs.put(key.substring(12 + name.length()), input.get(key));
            }
        }
        relevantArgs.put("name", name);
        return relevantArgs;
    }
    @Override
    public void process(final AioSession<HttpEntity> session, final HttpEntity entry) {
        //文件上传body部分的数据流需要由业务处理，又不可影响IO主线程

        if (org.apache.commons.lang.StringUtils.equalsIgnoreCase(entry.getMethod(), "POST")) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        process0(session, entry);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        } else {
            process0(session, entry);
        }
    }

    private void process0(AioSession<HttpEntity> session, HttpEntity entry) {
        ByteBuffer buffer = ByteBuffer.wrap(("HTTP/1.1 200 OK\n" +
                "Server: seer/1.4.4\n" +
                "Content-Length: 2\n" +
                ("Keep-Alive".equalsIgnoreCase(entry.getHeadMap().get("Connection")) ?
                        "Connection: keep-alive\n" : ""
                ) +
                "\n" +
                "OK").getBytes());
        try {
            session.write(buffer);
        } catch (IOException e) {
            LOGGER.catching(e);
        }
        if (!"Keep-Alive".equalsIgnoreCase(entry.getHeadMap().get("Connection"))) {
            session.close(false);
        }
        try {
            String servletURI = entry.getUrl();
            WinstoneRequest req = new WinstoneRequest(WinstoneConstant.DEFAULT_MAXIMUM_PARAMETER_ALLOWED);
            req.setHostGroup(hostGroup);
            WinstoneResponse rsp = new WinstoneResponse();
            HostConfiguration hostConfig = req.getHostGroup().getHostByName(req.getServerName());

            // Get the URI from the request, check for prefix, then
            // match it to a requestDispatcher
            WebAppConfiguration webAppConfig = hostConfig.getWebAppByURI(servletURI);
            if (webAppConfig == null) {
                webAppConfig = hostConfig.getWebAppByURI("/");
            }
            req.setWebAppConfig(webAppConfig);

            // Now we've verified it's in the right webapp, send
            // request in scope notify
            ServletRequestListener reqLsnrs[] = webAppConfig.getRequestListeners();
            for (ServletRequestListener reqLsnr1 : reqLsnrs) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(webAppConfig.getLoader());
                reqLsnr1.requestInitialized(new ServletRequestEvent(webAppConfig, req));
                Thread.currentThread().setContextClassLoader(cl);
            }

            // Lookup a dispatcher, then process with it
            processRequest(webAppConfig, req, rsp, webAppConfig.getServletURIFromRequestURI(servletURI));
            writeToAccessLog(servletURI, req, rsp, webAppConfig);

            // send request listener notifies
            for (ServletRequestListener reqLsnr : reqLsnrs) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(webAppConfig.getLoader());
                reqLsnr.requestDestroyed(new ServletRequestEvent(webAppConfig, req));
                Thread.currentThread().setContextClassLoader(cl);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ServletException e) {
            e.printStackTrace();
        }

    }

    private void processRequest(final WebAppConfiguration webAppConfig, final WinstoneRequest req, final WinstoneResponse rsp, final String path) throws IOException, ServletException {
        SimpleRequestDispatcher rd = null;
        javax.servlet.RequestDispatcher rdError = null;
        try {
            rd = webAppConfig.getInitialDispatcher(path, req, rsp);

            // Null RD means an error or we have been redirected to a welcome
            // page
            if (rd != null) {
                LOGGER.debug("Processing with RD: {}", rd.getName());
                rd.forward(req, rsp);
            }
            // if null returned, assume we were redirected
        } catch (final Throwable err) {
            boolean ignore = Boolean.FALSE;
            for (Throwable t = err; t != null; t = t.getCause()) {
                if (t instanceof ClientSocketException) {
                    ignore = Boolean.TRUE;
                    break;
                }
            }
            if (!ignore) {
                LOGGER.warn("Untrapped Error in Servlet", err);
                rdError = webAppConfig.getErrorDispatcherByClass(err);
            }
        }

        // If there was any kind of error, execute the error dispatcher here
        if (rdError != null) {
            try {
                if (rsp.isCommitted()) {
                    rdError.include(req, rsp);
                } else {
                    rsp.resetBuffer();
                    rdError.forward(req, rsp);
                }
            } catch (final Throwable err) {
                LOGGER.error("Error in the error servlet ", err);
            }
            // rsp.sendUntrappedError(err, req, rd != null ? rd.getName() :
            // null);
        }
        rsp.flushBuffer();
//        rsp.getWinstoneOutputStream().setClosed(Boolean.TRUE);
        req.discardRequestBody();
    }

    protected void writeToAccessLog(final String originalURL, final WinstoneRequest request, final WinstoneResponse response, final WebAppConfiguration webAppConfig) {
        if (webAppConfig != null) {
            // Log a row containing appropriate data
            final AccessLogger accessLogger = webAppConfig.getAccessLogger();
            if (accessLogger != null) {
                accessLogger.log(originalURL, request, response);
            }
        }
    }

    @Override
    public void stateEvent(AioSession<HttpEntity> session, StateMachineEnum stateMachineEnum, Throwable throwable) {

    }

}
