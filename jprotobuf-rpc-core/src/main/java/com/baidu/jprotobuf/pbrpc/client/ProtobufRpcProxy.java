/**
 * Copyright (C) 2017 Baidu, Inc. All Rights Reserved.
 */

package com.baidu.jprotobuf.pbrpc.client;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import com.baidu.jprotobuf.pbrpc.ClientAttachmentHandler;
import com.baidu.jprotobuf.pbrpc.ErrorDataException;
import com.baidu.jprotobuf.pbrpc.ProtobufRPC;
import com.baidu.jprotobuf.pbrpc.data.RpcDataPackage;
import com.baidu.jprotobuf.pbrpc.data.RpcResponseMeta;
import com.baidu.jprotobuf.pbrpc.data.Trace;
import com.baidu.jprotobuf.pbrpc.data.TraceContext;
import com.baidu.jprotobuf.pbrpc.intercept.InvokerInterceptor;
import com.baidu.jprotobuf.pbrpc.intercept.MethodInvocationInfo;
import com.baidu.jprotobuf.pbrpc.transport.BlockingRpcCallback;
import com.baidu.jprotobuf.pbrpc.transport.ChannelPoolSharableFactory;
import com.baidu.jprotobuf.pbrpc.transport.Connection;
import com.baidu.jprotobuf.pbrpc.transport.ExceptionHandler;
import com.baidu.jprotobuf.pbrpc.transport.GlobalChannelPoolSharableFactory;
import com.baidu.jprotobuf.pbrpc.transport.RpcChannel;
import com.baidu.jprotobuf.pbrpc.transport.RpcClient;
import com.baidu.jprotobuf.pbrpc.transport.RpcErrorMessage;
import com.baidu.jprotobuf.pbrpc.transport.SimpleChannelPoolSharableFactory;
import com.baidu.jprotobuf.pbrpc.transport.handler.ErrorCodes;
import com.baidu.jprotobuf.pbrpc.utils.ServiceSignatureUtils;
import com.baidu.jprotobuf.pbrpc.utils.StringUtils;
import com.baidu.jprotobuf.pbrpc.utils.TalkTimeoutController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protobuf RPC proxy utility class.
 *
 * @author xiemalin
 * @param <T> the generic type
 * @see ProxyFactory
 * @since 1.0
 */
public class ProtobufRpcProxy<T> implements InvocationHandler {

    /** Logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtobufRpcProxy.class);


    /** The Constant NULL. */
    private static final Object NULL = new Object();

    /** Logger for this class. */
    private static final Logger PERFORMANCE_LOGGER = LoggerFactory.getLogger("performance-log");

    /**
     * key name for shared RPC channel.
     *
     * @see RpcChannel
     */
    private static final String SHARE_KEY = "___share_key";

    /** The cached rpc methods. */
    private Map<String, RpcMethodInfo> cachedRpcMethods = new HashMap<String, RpcMethodInfo>();

    /**
     * RPC client.
     */
    private final RpcClient rpcClient;

    /** The rpc channel map. */
    private Map<String, RpcChannel> rpcChannelMap = new HashMap<String, RpcChannel>();

    /** The host. */
    private String host;

    /** The port. */
    private int port;

    /** The lookup stub on startup. */
    private boolean lookupStubOnStartup = false;

    /** The instance. */
    private T instance;

    /** The service locator callback. */
    private ServiceLocatorCallback serviceLocatorCallback;

    /** The service url. */
    private String serviceUrl;

    /** The interceptor. */
    private InvokerInterceptor interceptor;

    /** The exception handler. */
    private ExceptionHandler exceptionHandler;

    /** The channel pool sharable factory. */
    private ChannelPoolSharableFactory channelPoolSharableFactory;

    /**
     * Sets the channel pool sharable factory.
     *
     * @param channelPoolSharableFactory the new channel pool sharable factory
     */
    public void setChannelPoolSharableFactory(ChannelPoolSharableFactory channelPoolSharableFactory) {
        this.channelPoolSharableFactory = channelPoolSharableFactory;
    }

    /**
     * Sets the exception handler.
     *
     * @param exceptionHandler the new exception handler
     */
    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Sets the interceptor.
     *
     * @param interceptor the new interceptor
     */
    public void setInterceptor(InvokerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * Sets the service locator callback.
     *
     * @param serviceLocatorCallback the new service locator callback
     */
    public void setServiceLocatorCallback(ServiceLocatorCallback serviceLocatorCallback) {
        this.serviceLocatorCallback = serviceLocatorCallback;
    }

    /**
     * Checks if is lookup stub on startup.
     *
     * @return the lookup stub on startup
     */
    public boolean isLookupStubOnStartup() {
        return lookupStubOnStartup;
    }

    /**
     * Sets the lookup stub on startup.
     *
     * @param lookupStubOnStartup the new lookup stub on startup
     */
    public void setLookupStubOnStartup(boolean lookupStubOnStartup) {
        this.lookupStubOnStartup = lookupStubOnStartup;
    }

    /**
     * Sets the host.
     *
     * @param host the new host
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Gets the service signatures.
     *
     * @return the service signatures
     */
    public Set<String> getServiceSignatures() {
        if (!cachedRpcMethods.isEmpty()) {
            return new HashSet<String>(cachedRpcMethods.keySet());
        }

        Set<String> serviceSignatures = new HashSet<String>();
        Method[] methods = interfaceClass.getMethods();
        for (Method method : methods) {
            ProtobufRPC protobufPRC = method.getAnnotation(ProtobufRPC.class);
            if (protobufPRC != null) {
                String serviceName = protobufPRC.serviceName();
                String methodName = protobufPRC.methodName();
                if (StringUtils.isEmpty(methodName)) {
                    methodName = method.getName();
                }

                String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
                serviceSignatures.add(methodSignature);
            }
        }
        // if no protobufRpc methods defined throw exception
        if (serviceSignatures.isEmpty()) {
            throw new IllegalArgumentException(
                    "This no protobufRpc method in interface class:" + interfaceClass.getName());
        }
        return serviceSignatures;
    }

    /**
     * Sets the port.
     *
     * @param port the new port
     */
    public void setPort(int port) {
        this.port = port;
    }

    /** target interface class. */
    private final Class<T> interfaceClass;

    /**
     * Instantiates a new protobuf rpc proxy.
     *
     * @param rpcClient the rpc client
     * @param interfaceClass the interface class
     */
    public ProtobufRpcProxy(RpcClient rpcClient, Class<T> interfaceClass) {
        this.interfaceClass = interfaceClass;
        if (rpcClient == null) {
            throw new IllegalArgumentException("Param 'rpcClient'  is null.");
        }
        if (interfaceClass == null) {
            throw new IllegalArgumentException("Param 'interfaceClass'  is null.");
        }
        this.rpcClient = rpcClient;

    }

    /**
     * Gets the methds.
     *
     * @return the methds
     */
    protected Method[] getMethds() {
        return interfaceClass.getMethods();
    }

    /**
     * Proxy.
     *
     * @return the t
     */
    public synchronized T proxy() {

        if (instance != null) {
            return instance;
        }
        long startTime = System.currentTimeMillis();
        if (channelPoolSharableFactory == null) {

            boolean shareChannelPool = rpcClient.getRpcClientOptions().isShareChannelPool();
            if (shareChannelPool) {
                channelPoolSharableFactory = new GlobalChannelPoolSharableFactory();
                LOGGER.info(
                        "Use global share channel pool to create protobuf RPC proxy with interface " + interfaceClass);
            } else {
                channelPoolSharableFactory = new SimpleChannelPoolSharableFactory();
                LOGGER.info(
                        "Use Simple share channel pool to create protobuf RPC proxy with interface " + interfaceClass);
            }

        }

        // to parse interface
        Method[] methods = getMethds();
        for (Method method : methods) {
            ProtobufRPC protobufPRC = getProtobufRPCAnnotation(method);
            if (protobufPRC != null) {
                String serviceName = protobufPRC.serviceName();
                String methodName = protobufPRC.methodName();
                if (StringUtils.isEmpty(methodName)) {
                    methodName = method.getName();
                }

                String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
                if (cachedRpcMethods.containsKey(methodSignature)) {
                    throw new IllegalArgumentException(
                            "Method with annotation ProtobufPRC already defined service name [" + serviceName
                                    + "] method name [" + methodName + "]");
                }

                RpcMethodInfo methodInfo;
                if (!RpcMethodInfo.isMessageType(method)) {
                    // using POJO
                    methodInfo = new PojoRpcMethodInfo(method, protobufPRC);

                } else {
                    // support google protobuf GeneratedMessage
                    methodInfo = new GeneratedMessageRpcMethodInfo(method, protobufPRC);
                }
                methodInfo.setOnceTalkTimeout(protobufPRC.onceTalkTimeout());
                methodInfo.setServiceName(serviceName);
                methodInfo.setMethodName(methodName);

                cachedRpcMethods.put(methodSignature, methodInfo);

                // do create rpc channal
                String eHost = host;
                int ePort = port;
                if (serviceLocatorCallback != null) {
                    InetSocketAddress address = serviceLocatorCallback.fetchAddress(methodSignature);
                    if (address == null) {
                        throw new RuntimeException("fetch a null address from serviceLocatorCallback"
                                + " by serviceSignature '" + methodSignature + "'");
                    }
                    eHost = address.getHostName();
                    ePort = address.getPort();
                }

                String channelKey = methodSignature;

                if (rpcClient.getRpcClientOptions().isShareThreadPoolUnderEachProxy()) {
                    channelKey = SHARE_KEY;
                }

                if (!rpcChannelMap.containsKey(channelKey)) {
                    RpcChannel rpcChannel = channelPoolSharableFactory.getOrCreateChannelPool(rpcClient, eHost, ePort);
                    if (lookupStubOnStartup) {
                        rpcChannel.testChannlConnect();
                    }

                    rpcChannelMap.put(channelKey, rpcChannel);
                }

                serviceUrl = eHost + ":" + ePort;
            }
        }

        // if not protobufRpc method defined throw exception
        if (cachedRpcMethods.isEmpty()) {
            throw new IllegalArgumentException(
                    "This no protobufRpc method in interface class:" + interfaceClass.getName());
        }
        long afterProcessMethodTime = System.currentTimeMillis();
        LOGGER.info("process method cost {} ms, method num {}", afterProcessMethodTime - startTime, methods.length);
        Class[] clazz = { interfaceClass, ServiceUrlAccessible.class };
        instance = ProxyFactory.createProxy(clazz, interfaceClass.getClassLoader(), this);
        LOGGER.info("create a new proxy cost {} ms, addr: {}:{}", System.currentTimeMillis() - afterProcessMethodTime, host, port);
        return instance;
    }

    /**
     * Builds the request data package.
     *
     * @param rpcMethodInfo the rpc method info
     * @param args the args
     * @return the rpc data package
     * @throws IOException Signals that an I/O exception has occurred.
     */
    protected RpcDataPackage buildRequestDataPackage(RpcMethodInfo rpcMethodInfo, Object[] args) throws IOException {
        RpcDataPackage rpcDataPackage = RpcDataPackage.buildRpcDataPackage(rpcMethodInfo, args);
        
        // set trace info
        Trace trace = TraceContext.getTrace();
        if (trace != null) {
            rpcDataPackage.trace(trace);
        }
        
        return rpcDataPackage;
    }

    /**
     * Close.
     */
    public void close() {
        Collection<RpcChannel> rpcChannels = rpcChannelMap.values();
        for (RpcChannel rpcChann : rpcChannels) {
            try {
                rpcChann.close();
            } catch (Exception e) {
                LOGGER.warn(e.getMessage(), e.getCause());
            }
        }
        rpcChannelMap.clear();

    }

    /**
     * Process equals hash code to string method.
     *
     * @param method the method
     * @param args the args
     * @return the object
     */
    private Object processEqualsHashCodeToStringMethod(Method method, final Object[] args) {
        String name = method.getName();

        Object[] parameters = args;
        if (parameters == null) {
            parameters = new Object[0];
        }

        if ("toString".equals(name) && parameters.length == 0) {
            return serviceUrl;
        } else if ("hashCode".equals(name) && parameters.length == 0) {
            return serviceUrl.hashCode();
        } else if ("equals".equals(name) && parameters.length == 1) {
            return this.equals(parameters[0]);
        }

        return NULL;
    }

    /**
     * Gets the protobuf rpc annotation.
     *
     * @param method the method
     * @return the protobuf rpc annotation
     */
    protected ProtobufRPC getProtobufRPCAnnotation(Method method) {
        ProtobufRPC protobufPRC = method.getAnnotation(ProtobufRPC.class);
        return protobufPRC;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.reflect.InvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method, java.lang.Object[])
     */
    public Object invoke(Object proxy, final Method method, final Object[] args) throws Throwable {

        String mName = method.getName();
        if ("getServiceUrl".equals(mName)) {

            // return directly from local due to call ServiceUrlAccessible
            return serviceUrl;
        }

        Object result = processEqualsHashCodeToStringMethod(method, args);
        if (result != NULL) {
            return result;
        }

        final long time = System.currentTimeMillis();

        ProtobufRPC protobufPRC = getProtobufRPCAnnotation(method);
        if (protobufPRC == null) {
            throw new IllegalAccessError("Target method is not marked annotation @ProtobufPRC. method name :"
                    + method.getDeclaringClass().getName() + "." + method.getName());
        }

        final String serviceName = protobufPRC.serviceName();
        String methodName = protobufPRC.methodName();
        if (StringUtils.isEmpty(methodName)) {
            methodName = mName;
        }
        String methodSignature = ServiceSignatureUtils.makeSignature(serviceName, methodName);
        final RpcMethodInfo rpcMethodInfo = cachedRpcMethods.get(methodSignature);
        if (rpcMethodInfo == null) {
            throw new IllegalAccessError(
                    "Can not invoke method '" + method.getName() + "' due to not a protbufRpc method.");
        }

        long onceTalkTimeout = rpcMethodInfo.getOnceTalkTimeout();
        if (onceTalkTimeout <= 0) {
            // use default once talk timeout
            onceTalkTimeout = rpcClient.getRpcClientOptions().getOnceTalkTimeout();
        }

        RpcDataPackage rpcDataPackage = buildRequestDataPackage(rpcMethodInfo, args);
        // set correlationId
        rpcDataPackage.getRpcMeta().setCorrelationId(rpcClient.getNextCorrelationId());

        String channelKey = methodSignature;
        if (rpcClient.getRpcClientOptions().isShareThreadPoolUnderEachProxy()) {
            channelKey = SHARE_KEY;
        }

        try {
            // check intercepter
            if (interceptor != null) {

                byte[] extraParams = rpcDataPackage.getRpcMeta().getRequest().getExtraParam();
                Map<String, String> extFields = rpcDataPackage.getRpcMeta().getRequest().getExtFieldsAsMap();
                MethodInvocationInfo methodInvocationInfo =
                        new MethodInvocationInfo(proxy, args, method, extraParams, extFields);
                interceptor.beforeInvoke(methodInvocationInfo);

                Object ret = interceptor.process(methodInvocationInfo);
                if (ret != null) {
                    LOGGER.debug("RPC client invoke method(by intercepter) '" + method.getName()
                            + "' time took:" + (System.currentTimeMillis() - time) + " ms");
                    return ret;
                }

                rpcDataPackage.extraParams(methodInvocationInfo.getExtraParams());
            }

            final RpcChannel rpcChannel = rpcChannelMap.get(channelKey);
            if (rpcChannel == null) {
                throw new RuntimeException("No rpcChannel bind with serviceSignature '" + channelKey + "'");
            }

            final Connection connection = rpcChannel.getConnection();

            BlockingRpcCallback.CallbackDone callbackDone = null;
            if (!rpcClient.getRpcClientOptions().isInnerResuePool()) {
                callbackDone = new BlockingRpcCallback.CallbackDone() {
                    @Override
                    public void done() {
                        if (rpcChannel != null) {
                            rpcChannel.releaseConnection(connection);
                        }
                    }

                };
            }

            final BlockingRpcCallback callback = new BlockingRpcCallback(callbackDone);

            // to check time out setting if need
            long talkTimeout = TalkTimeoutController.getTalkTimeout();
            if (talkTimeout > 0) {
                /*
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE,
                            "talk time out is changed by TalkTimeoutController new value is '" + talkTimeout + "'");
                }*/
                onceTalkTimeout = talkTimeout;
            }

            if (TalkTimeoutController.isEnableOnce()) {
                TalkTimeoutController.clearTalkTimeout();
            }

            try {
                rpcChannel.doTransport(connection, rpcDataPackage, callback, onceTalkTimeout);
            } finally {
                if (rpcClient.getRpcClientOptions().isInnerResuePool() && rpcChannel != null) {
                    rpcChannel.releaseConnection(connection);
                }
            }

            final String m = methodName;
            if (method.getReturnType().isAssignableFrom(Future.class)) {
                // if use non-blocking call
                Future<Object> f = new Future<Object>() {

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean isDone() {
                        return callback.isDone();
                    }

                    @Override
                    public Object get() throws InterruptedException, ExecutionException {
                        try {

                            Object o = doWaitCallback(method, args, serviceName, m, rpcMethodInfo, callback, -1, null);
                            LOGGER.debug("RPC client invoke method '" + method.getName() + "' time took:"
                                    + (System.currentTimeMillis() - time) + " ms, callback: " + callback);
                            return o;
                        } catch (Exception e) {
                            throw new ExecutionException(e.getMessage(), e);
                        }
                    }

                    @Override
                    public Object get(long timeout, TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {

                        try {
                            // LOGGER.debug("RPC client invoke method " + method.getName());
                            Object o = doWaitCallback(method, args, serviceName, m, rpcMethodInfo, callback, timeout, unit);
                            LOGGER.debug("RPC client invoke method '" + method.getName() + "' time took:"
                                    + (System.currentTimeMillis() - time) + " ms, callback: " + callback);
                            return o;
                        } catch (Exception e) {
                            throw new ExecutionException(e.getMessage(), e);
                        }

                    }
                };

                return f;
            }

            Object o = doWaitCallback(method, args, serviceName, methodName, rpcMethodInfo, callback, -1, null);

            LOGGER.debug("RPC client invoke method '" + method.getName() + "' time took:"
                    + (System.currentTimeMillis() - time) + " ms");
            return o;
        } finally {
            if (interceptor != null) {
                interceptor.afterProcess();
            }
        }
    }

    /**
     * do wait {@link BlockingRpcCallback} return.
     *
     * @param method java method object
     * @param args method arguments
     * @param serviceName service name
     * @param methodName method name
     * @param rpcMethodInfo RPC method info
     * @param callback {@link BlockingRpcCallback} object
     * @return RPC result
     * @throws Exception the exception
     */
    private Object doWaitCallback(Method method, Object[] args, String serviceName, String methodName,
            RpcMethodInfo rpcMethodInfo, BlockingRpcCallback callback, long timeout, TimeUnit unit) throws Exception {

        BlockingRpcCallback c = callback;

        if (!c.isDone()) {
            long timeExpire = 0;
            if (timeout > 0 && unit != null) {
                timeExpire = System.currentTimeMillis() + unit.toMillis(timeout);
            }
            while (!c.isDone()) {
                synchronized (c) {
                    try {
                        if (timeExpire > 0 && System.currentTimeMillis() > timeExpire) {
                            throw new TimeoutException("Ocurrs time out with specfied time " + timeout + " " + unit);
                        }
                        c.wait(10L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        long callbackDoneTime = System.currentTimeMillis();
        LOGGER.debug("wait callback cost {} ms, callback {}", callbackDoneTime - c.getCreateTime(), c);

        RpcDataPackage message = c.getMessage();

        RpcResponseMeta response = message.getRpcMeta().getResponse();
        if (response != null) {
            Integer errorCode = response.getErrorCode();
            if (!ErrorCodes.isSuccess(errorCode)) {
                if (exceptionHandler != null) {

                    RpcErrorMessage rpcErrorMessage = new RpcErrorMessage(errorCode, response.getErrorText());
                    Exception exception = exceptionHandler.handleException(rpcErrorMessage);
                    if (exception != null) {
                        throw exception;
                    }

                } else {
                    String error = message.getRpcMeta().getResponse().getErrorText();
                    throw new ErrorDataException("A error occurred: errorCode=" + errorCode + " errorMessage:" + error,
                            errorCode);
                }

            }
        }

        byte[] attachment = message.getAttachment();
        if (attachment != null) {
            ClientAttachmentHandler attachmentHandler = rpcMethodInfo.getClientAttachmentHandler();
            if (attachmentHandler != null) {
                attachmentHandler.handleResponse(attachment, serviceName, methodName, args);
            }
        }
        long handleResponseDoneTime = System.currentTimeMillis();
        LOGGER.debug("handle response cost {} ms, callback {}", handleResponseDoneTime - callbackDoneTime, c);
        // handle response data
        byte[] data = message.getData();
        if (data == null) {
            return null;
        }

        Object o = rpcMethodInfo.outputDecode(data);
        LOGGER.debug("outputDecode cost {} ms, data len {}, callback {}",
                System.currentTimeMillis() - handleResponseDoneTime, data.length, c);
        return o;
    }

}
