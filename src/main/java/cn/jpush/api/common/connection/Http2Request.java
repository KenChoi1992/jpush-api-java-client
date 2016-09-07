package cn.jpush.api.common.connection;

public class Http2Request {

    private String _url;
    private String _content;

    public Http2Request(String url, String content) {
        this._url = url;
        this._content = content;
    }

    public String getUrl() {
        return this._url;
    }

    public String getContent() {
        return this._content;
    }
}
