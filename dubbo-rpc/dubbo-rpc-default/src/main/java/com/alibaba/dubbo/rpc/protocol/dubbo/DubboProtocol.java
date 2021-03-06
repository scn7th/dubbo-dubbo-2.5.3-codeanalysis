/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.dubbo;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporter;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

/**
 * dubbo protocol support.
 *
 * @author qian.lei
 * @author william.liangf
 * @author chao.liuc
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final String COMPATIBLE_CODEC_NAME = "dubbo1compatible";
    
    public static final int DEFAULT_PORT = 20880;
    
    public final ReentrantLock lock = new ReentrantLock();
    
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>(); // <host:port,Exchanger>
    
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>
    
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    
    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();
    
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        /**
         * （1）通过getInvoker(channel, inv)获取与指定服务对应的Invoker(服务暴露时已创建好)
         * （2）通过Invoker调用具体的服务
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        public Object reply(ExchangeChannel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                Invocation inv = (Invocation) message;
                // 获取 Invoker 实例
                Invoker<?> invoker = getInvoker(channel, inv);

                //如果是callback 需要处理高版本调用低版本的问题
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))){
                    // 回调相关，忽略
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    if (methodsStr == null || methodsStr.indexOf(",") == -1){
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    } else {
                        String[] methods = methodsStr.split(",");
                        for (String method : methods){
                            if (inv.getMethodName().equals(method)){
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    if (!hasMethod){
                        logger.warn(new IllegalStateException("The methodName "+inv.getMethodName()+" not found in callback service interface ,invoke will be ignored. please update the api interface. url is:" + invoker.getUrl()) +" ,invocation is :"+inv );
                        return null;
                    }
                }

                // 通过 Invoker 调用具体的服务，AbstractProxyInvoker#invoke
                RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
                return invoker.invoke(inv);
            }
            throw new RemotingException(channel, "Unsupported request: " + message == null ? null : (message.getClass().getName() + ": " + message) + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if(logger.isInfoEnabled()){
                logger.info("disconected from "+ channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }
        
        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }
        
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)){
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };
    
    private static DubboProtocol INSTANCE;

    public DubboProtocol() {
        INSTANCE = this;
    }
    
    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); // load
        }
        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }
    
    Map<String, Exporter<?>> getExporterMap(){
        return exporterMap;
    }
    
    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() && 
                    NetUtils.filterLocalHost(channel.getUrl().getIp())
                    .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }
    
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException{
        //start **********  回调和本地存根相关逻辑
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = inv.getAttachments().get(Constants.PATH_KEY);
        //如果是客户端的回调服务.
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke){
            port = channel.getRemoteAddress().getPort();
        }
        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if(isCallBackServiceInvoke){
            path = inv.getAttachments().get(Constants.PATH_KEY)+"."+inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        //end **********  回调和本地存根相关逻辑

        // 计算 service key，格式为 groupName/serviceName:serviceVersion:port。比如：
        //   dubbo/com.alibaba.dubbo.demo.DemoService:1.0.0:20880
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));

        // 从 exporterMap 查找与 serviceKey 相对应的 DubboExporter 对象，
        // 服务导出过程中会将 <serviceKey, DubboExporter> 映射关系存储到 exporterMap 集合中
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);
        
        if (exporter == null)
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);

        // 获取 Invoker 对象，并返回
        return exporter.getInvoker();
    }
    
    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 服务导出整体逻辑：
     * （1）创建exporter：DubboExporter
     * （2）openServer()启动服务：根据url启动一个服务，比如绑定端口，开始接受请求信息
     * （3）返回exporter
     * @param invoker 服务的执行体
     * @param <T>
     * @return
     * @throws RpcException
     */
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();
        
        // export service.

        //key的组成：group/service:version:port，group和version可能为空
        //eg：com.kl.dubbotest.provider.export.ProviderExport:20880
        String key = serviceKey(url);


        //构造服务的exporter
        //如同InjvmProtocol，DubboProtocol也是单例的 故exporterMap也是单例的
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        //通过key放入exporterMap，把持有invoker的exporter 和serviceKey关联
        //可以通过key找到对应的exporter进而找到invoker提供服务
        exporterMap.put(key, exporter);
        
        //export an stub service for dispaching event 本地存根相关代码
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY,Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice){
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0 ){
                if (logger.isWarnEnabled()){
                    logger.warn(new IllegalStateException("consumer [" +url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        //启动服务：根据url启动一个服务，比如绑定端口，开始接受请求信息
        openServer(url);
        
        return exporter;
    }

    /**
     * 启动服务：
     * （1）从缓存获取
     *      map{key host:port, val ExchangeServer}
     * （2）缓存获取不到，则创建服务createServer(url)
     * （3）缓存获取到了，则重置服务reset(url)
     * @param url
     */
    private void openServer(URL url) {
        // find server. key=host:port 用于定位server
        String key = url.getAddress();
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY,true);
        if (isServer) {
        	ExchangeServer server = serverMap.get(key);
        	if (server == null) {
                //创建服务器实例
        		serverMap.put(key, createServer(url));
        	} else {
        		//服务器已创建，则根据 url 中的配置重置服务器
        		server.reset(url);
        	}
        }
    }

    /**
     * 开启服务
     * @param url
     * @return
     */
    private ExchangeServer createServer(URL url) {
        //默认开启 server关闭时发送readonly事件
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        //默认开启 heartbeat，添加心跳检测配置到 url 中
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        // 获取 server 参数，默认为 netty
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        // 通过 SPI 检测是否存在Transporter拓展，不存在则抛出异常
        if (str != null && str.length() > 0 && ! ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);

        // 添加编码解码器参数，兼容dubbo1,默认是dubbo1compatible ，否则默认dubbo 编解码方案
        url = url.addParameter(Constants.CODEC_KEY, Version.isCompatibleVersion() ? COMPATIBLE_CODEC_NAME : DubboCodec.NAME);
        ExchangeServer server;
        try {
            //构造具体服务实例，
            //Exchangers是门面类，里面封装了具体交换层实现，并调用它的bind方法
            // 创建 ExchangeServer
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 获取 client 参数，可指定 netty，mina
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 是否包含 client 所表示的 Transporter，若不包含，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    /**
     * 在服务提供方，Invoker 用于调用服务提供类。在服务消费方，Invoker 用于执行远程调用。
     * Invoker是由Protocol实现类构建而来
     * @param serviceType 扩张接口类Class
     * @param url 远程服务的URL地址
     * @param <T>
     * @return
     * @throws RpcException
     */
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // create rpc invoker. 创建 DubboInvoker
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);
        return invoker;
    }

    /**
     * ExchangeClient不具备通信能力，底层的NettyClient具备通信能力
     * （1）判断是创建共享连接还是多个连接(每个服务每个连接)，默认为共享连接
     * （2）创建连接ExchangeClient
     * @param url
     * @return
     */
    private ExchangeClient[] getClients(URL url){
        //是否共享连接
        boolean service_share_connect = false;
        // 获取连接数，默认为0，表示未配置
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        //如果connections不配置，则共享连接，否则每服务每连接
        if (connections == 0){
            service_share_connect = true;
            connections = 1;
        }
        
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (service_share_connect){
                // 获取共享客户端
                clients[i] = getSharedClient(url);
            } else {
                // 创建新的客户端
                clients[i] = initClient(url);
            }
        }
        return clients;
    }
    
    /**
     * 获取共享连接
     * （1）从缓存获取ExchangeClient
     * （2）缓存获取不到，调initClient(url)创建ExchangeClient，并使用ReferenceCountExchangeClient包装ExchangeClient
     * （3）置入缓存
     */
    private ExchangeClient getSharedClient(URL url){
        String key = url.getAddress();
        // 获取带有“引用计数”功能的 ExchangeClient
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        if ( client != null ){
            if ( !client.isClosed()){
                // 增加引用计数
                client.incrementAndGetCount();
                return client;
            } else {
//                logger.warn(new IllegalStateException("client is closed,but stay in clientmap .client :"+ client));
                referenceClientMap.remove(key);
            }
        }

        // 创建 ExchangeClient 客户端
        ExchangeClient exchagneclient = initClient(url);
        // 将 ExchangeClient 实例传给 ReferenceCountExchangeClient，这里使用了装饰模式
        client = new ReferenceCountExchangeClient(exchagneclient, ghostClientMap);
        referenceClientMap.put(key, client);
        ghostClientMap.remove(key);
        return client; 
    }

    /**
     * 创建新连接.ExchangeClient实例
     */
    private ExchangeClient initClient(URL url) {
        
        // client type setting. 获取客户端类型，默认为 netty
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        String version = url.getParameter(Constants.DUBBO_VERSION_KEY);
        boolean compatible = (version != null && version.startsWith("1.0."));
        // 添加编解码参数到url中
        url = url.addParameter(Constants.CODEC_KEY, Version.isCompatibleVersion() && compatible ? COMPATIBLE_CODEC_NAME : DubboCodec.NAME);
        //添加心跳包参数到url中，默认开启heartbeat
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        
        // BIO存在严重性能问题，暂时不允许使用
        // 检测客户端类型是否存在，不存在则抛出异常
        if (str != null && str.length() > 0 && ! ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }
        
        ExchangeClient client ;
        try {
            //设置连接应该是lazy的
            // 获取 lazy 配置，并根据配置值决定创建的客户端类型
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)){
                // 创建懒加载 ExchangeClient 实例
                client = new LazyConnectExchangeClient(url ,requestHandler);
            } else {
                // 创建普通 ExchangeClient 实例
                client = Exchangers.connect(url ,requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url
                    + "): " + e.getMessage(), e);
        }
        return client;
    }

    public void destroy() {
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    server.close(getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        
        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        
        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        stubServiceMethodsMap.clear();
        super.destroy();
    }
}