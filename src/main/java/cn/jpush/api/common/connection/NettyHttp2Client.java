package cn.jpush.api.common.connection;

import cn.jpush.api.common.ClientConfig;
import cn.jpush.api.common.resp.APIConnectionException;
import cn.jpush.api.common.resp.APIRequestException;
import cn.jpush.api.common.resp.ResponseWrapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.Authenticator;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class NettyHttp2Client implements IHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(NettyHttp2Client.class);
    private static final String KEYWORDS_CONNECT_TIMED_OUT = "connect timed out";
    private static final String KEYWORDS_READ_TIMED_OUT = "Read timed out";

    private final int _connectionTimeout;
    private final int _readTimeout;
    private final int _maxRetryTimes;
    private final String _sslVer;
    static final boolean SSL = true;//System.getProperty("ssl") != null;
    static final int PORT = 443;
    private String _authCode;
    private HttpProxy _proxy;
    Bootstrap b = new Bootstrap();
    Http2ClientInitializer initializer;
    public Map<String, List<String>> responseMap = new HashMap<String, List<String>>();
    NettyHttp2Client mNettyHttp2Client;
    private String mChannelId;
    private String mKey;

    public NettyHttp2Client(String authCode, HttpProxy proxy, ClientConfig config) {
        _maxRetryTimes = config.getMaxRetryTimes();
        _connectionTimeout = config.getConnectionTimeout();
        _readTimeout = config.getReadTimeout();
        _sslVer = config.getSSLVersion();

        _authCode = authCode;
        _proxy = proxy;

        String message = MessageFormat.format("Created instance with "
                        + "connectionTimeout {0}, readTimeout {1}, maxRetryTimes {2}, SSL Version {3}",
                _connectionTimeout, _readTimeout, _maxRetryTimes, _sslVer);
        LOG.info(message);

        if (null != _proxy && _proxy.isAuthenticationNeeded()) {
            Authenticator.setDefault(new NativeHttpClient.SimpleProxyAuthenticator(
                    _proxy.getUsername(), _proxy.getPassword()));
        }

        // Configure SSL.
        SslContext sslCtx = null;
        SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
        try {
            sslCtx = SslContextBuilder.forClient()
                    .sslProvider(provider)
                    /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                     * Please refer to the HTTP/2 specification for cipher requirements. */
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
        } catch (SSLException e) {
            e.printStackTrace();
        }


        EventLoopGroup workerGroup = new NioEventLoopGroup();
        initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE, this);


        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);

        mNettyHttp2Client = this;
    }

    public void initNettyHttp2Client(String host, String url, RequestMethod method, String content) throws Exception {

        b.remoteAddress(host, PORT);
        b.handler(initializer);
        // Start the client.
        Channel channel = b.connect().syncUninterruptibly().channel();
        mChannelId = channel.id().asShortText();
        LOG.debug("ShortText channel id: " + mChannelId);
        LOG.debug("Connected to [" + host + ':' + PORT + ']');

        // Wait for the HTTP/2 upgrade to occur.
        Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
        http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);

        HttpResponseHandler responseHandler = initializer.responseHandler();
        HttpScheme scheme = SSL ? HttpScheme.HTTPS : HttpScheme.HTTP;
        AsciiString hostName = new AsciiString(host + ':' + PORT);
        System.err.println("Sending request(s)...");
        int streamId = 3;
        FullHttpRequest request;
        if (method == RequestMethod.GET) {
            // Create a simple GET request.
            request = new DefaultFullHttpRequest(HTTP_1_1, GET, url);
            request.headers().add(HttpHeaderNames.HOST, hostName);
            request.headers().add(HttpHeaderNames.AUTHORIZATION, _authCode);
            request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
            if (null != content) {
                byte[] data = content.getBytes(CHARSET);
                request.headers().add(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
            }
            responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
            streamId += 2;
        } else if (method == RequestMethod.POST) {
            // Create a simple POST request with a body.
            request = new DefaultFullHttpRequest(HTTP_1_1, POST, url,
                    Unpooled.copiedBuffer(content.getBytes(CharsetUtil.UTF_8)));
            request.headers().add(HttpHeaderNames.HOST, hostName);
            request.headers().add(HttpHeaderNames.AUTHORIZATION, _authCode);
            request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
            responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
            streamId += 2;
        } else if (method == RequestMethod.PUT) {
            request = new DefaultFullHttpRequest(HTTP_1_1, PUT, url,
                    Unpooled.copiedBuffer(content.getBytes(CharsetUtil.UTF_8)));
            request.headers().add(HttpHeaderNames.HOST, hostName);
            request.headers().add(HttpHeaderNames.AUTHORIZATION, _authCode);
            request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
            responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
            streamId += 2;
        } else if (method == RequestMethod.DELETE) {
            if (null != content) {
                 request = new DefaultFullHttpRequest(HTTP_1_1, DELETE, url,
                        Unpooled.copiedBuffer(content.getBytes(CharsetUtil.UTF_8)));
            } else {
                request = new DefaultFullHttpRequest(HTTP_1_1, DELETE, url);
            }
            request.headers().add(HttpHeaderNames.HOST, hostName);
            request.headers().add(HttpHeaderNames.AUTHORIZATION, _authCode);
            request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
            request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
            responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
            streamId += 2;
        }
        responseHandler.awaitResponses(5, TimeUnit.SECONDS);
        System.out.println("Finished HTTP/2 request(s)");

        // Wait until the connection is closed.
        channel.close().syncUninterruptibly();


    }

    public void setResponse(String key, List<String> list) {
        mKey = key;
        responseMap.put(key, list);
    }

    @Override
    public ResponseWrapper sendGet(String url) throws APIConnectionException, APIRequestException {
        return sendGet(url, null);
    }

    public ResponseWrapper sendGet(String url, String content) throws APIConnectionException, APIRequestException {
//        ResponseWrapper wrapper = new ResponseWrapper();
//        try {
//            initNettyHttp2Client(url, "GET", content);
//            handleResponse(wrapper);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return wrapper;
        return null;
    }

    @Override
    public ResponseWrapper sendDelete(String url) throws APIConnectionException, APIRequestException {
        return null;
    }

    @Override
    public ResponseWrapper sendPost(String url, String content) throws APIConnectionException, APIRequestException {
//        ResponseWrapper wrapper = new ResponseWrapper();
//        try {
//            initNettyHttp2Client(url.substring(8), "POST", content);
//            handleResponse(wrapper);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return wrapper;
        return null;
    }

    @Override
    public ResponseWrapper sendPut(String url, String content) throws APIConnectionException, APIRequestException {
        return null;
    }

    @Override
    public ResponseWrapper sendPut(String host, String url, String content) throws APIConnectionException, APIRequestException {
        ResponseWrapper wrapper = new ResponseWrapper();
        try {
            initNettyHttp2Client(host.substring(8),url, RequestMethod.PUT, content);
            handleResponse(wrapper);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return wrapper;
    }

    @Override
    public ResponseWrapper sendPost(String host, String url, String content) throws APIConnectionException, APIRequestException {
        ResponseWrapper wrapper = new ResponseWrapper();
        try {
            initNettyHttp2Client(host.substring(8),url, RequestMethod.POST, content);
            handleResponse(wrapper);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return wrapper;
    }

    @Override
    public ResponseWrapper sendDelete(String host, String url, String content) throws APIConnectionException, APIRequestException {
        ResponseWrapper wrapper = new ResponseWrapper();
        try {
            initNettyHttp2Client(host.substring(8), url, RequestMethod.DELETE, content);
            handleResponse(wrapper);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return wrapper;
    }

    public void handleResponse(ResponseWrapper wrapper) {
        List<String> responseList = responseMap.get(mKey);
        if (null != responseList) {
            int status = Integer.valueOf(responseList.get(0));
            wrapper.responseCode = status;
            if (responseList.size() > 1) {
                wrapper.responseContent = responseList.get(1);
            }
            if (status / 200 == 1) {
                LOG.debug("Succeed to get response OK - response body: " + wrapper.responseContent);
            } else if (status >= 300 && status < 400) {
                LOG.warn("Normal response but unexpected - responseCode:" + status + ", responseContent:" + wrapper.responseContent);
                wrapper.setErrorObject();
            } else {
                LOG.warn("Got error response - responseCode:" + status + ", responseContent:" + wrapper.responseContent);

                switch (status) {
                    case 400:
                        LOG.error("Your request params is invalid. Please check them according to error message.");
                        wrapper.setErrorObject();
                        break;
                    case 401:
                        LOG.error("Authentication failed! Please check authentication params according to docs.");
                        wrapper.setErrorObject();
                        break;
                    case 403:
                        LOG.error("Request is forbidden! Maybe your appkey is listed in blacklist or your params is invalid.");
                        wrapper.setErrorObject();
                        break;
                    case 404:
                        LOG.error("Request page is not found! Maybe your params is invalid.");
                        wrapper.setErrorObject();
                        break;
                    case 410:
                        LOG.error("Request resource is no longer in service. Please according to notice on official website.");
                        wrapper.setErrorObject();
                    case 429:
                        LOG.error("Too many requests! Please review your appkey's request quota.");
                        wrapper.setErrorObject();
                        break;
                    case 500:
                    case 502:
                    case 503:
                    case 504:
                        LOG.error("Seems encountered server error. Maybe JPush is in maintenance? Please retry later.");
                        wrapper.setErrorObject();
                        break;
                    default:
                        LOG.error("Unexpected response.");
                        wrapper.setErrorObject();
                }
            }
        } else {
            LOG.error("Unexpected response.");
            wrapper.setErrorObject();
        }


    }
}
