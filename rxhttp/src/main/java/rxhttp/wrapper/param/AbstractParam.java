package rxhttp.wrapper.param;

import java.util.LinkedHashMap;
import java.util.Map;

import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;
import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.Headers.Builder;
import okhttp3.HttpUrl;
import rxhttp.wrapper.utils.BuildUtil;

/**
 * 此类是唯一直接实现Param接口的类
 * User: ljx
 * Date: 2019/1/19
 * Time: 14:35
 */
@SuppressWarnings("unchecked")
public abstract class AbstractParam<P extends Param> implements Param<P> {

    private String mUrl;    //链接地址
    private Method mMethod;  //请求方法
    private Builder mHBuilder; //请求头构造器
    private Map<String, Object> mParam; //请求参数

    private Object mTag;
    private CacheControl mCacheControl;

    private boolean mIsAssemblyEnabled = true;//是否添加公共参数

    /**
     * @param url    请求路径
     * @param method {@link Method#GET,Method#HEAD,Method#POST,Method#PUT,Method#DELETE,Method#PATCH}
     */
    public AbstractParam(@NonNull String url, Method method) {
        this.mUrl = url;
        this.mMethod = method;
    }

    public P setUrl(@NonNull String url) {
        mUrl = url;
        return (P) this;
    }

    @Override
    public HttpUrl getHttpUrl() {
        return HttpUrl.get(mUrl);
    }

    /**
     * @return 不带参数的url
     */
    @Override
    public final String getSimpleUrl() {
        return mUrl;
    }

    @Override
    public Method getMethod() {
        return mMethod;
    }

    @Nullable
    @Override
    public final Headers getHeaders() {
        return mHBuilder == null ? null : mHBuilder.build();
    }

    @Override
    public final Builder getHeadersBuilder() {
        if (mHBuilder == null)
            mHBuilder = new Builder();
        return mHBuilder;
    }

    @Override
    public P setHeadersBuilder(Builder builder) {
        mHBuilder = builder;
        return (P) this;
    }

    @Override
    public final P addHeader(String key, String value) {
        getHeadersBuilder().add(key, value);
        return (P) this;
    }

    @Override
    public final P addHeader(String line) {
        getHeadersBuilder().add(line);
        return (P) this;
    }

    @Override
    public final P setHeader(String key, String value) {
        getHeadersBuilder().set(key, value);
        return (P) this;
    }

    @Override
    public final String getHeader(String key) {
        return getHeadersBuilder().get(key);
    }

    @Override
    public final P removeAllHeader(String key) {
        getHeadersBuilder().removeAll(key);
        return (P) this;
    }

    @Override
    public final P add(String key, Object value) {
        if (value == null) value = "";
        Map<String, Object> param = mParam;
        if (param == null) {
            param = mParam = new LinkedHashMap<>();
        }
        param.put(key, value);
        return (P) this;
    }

    @Override
    public P add(Map<? extends String, ?> map) {
        if (map != null && map.size() > 0) {
            Map<String, Object> param = mParam;
            if (param == null) {
                param = mParam = new LinkedHashMap<>();
            }
            param.putAll(map);
        }
        return (P) this;
    }

    @Override
    public final Map<String, Object> getParams() {
        return mParam;
    }

    @Override
    public CacheControl getCacheControl() {
        return mCacheControl;
    }

    @Override
    public P cacheControl(CacheControl cacheControl) {
        mCacheControl = cacheControl;
        return (P) this;
    }

    @Override
    public P tag(Object tag) {
        mTag = tag;
        return (P) this;
    }

    @Override
    public Object getTag() {
        return mTag;
    }

    @Override
    public final P setAssemblyEnabled(boolean enabled) {
        mIsAssemblyEnabled = enabled;
        return (P) this;
    }

    @Override
    public final boolean isAssemblyEnabled() {
        return mIsAssemblyEnabled;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " {" +
            "  \nurl = " + mUrl + '\'' +
            ", \nparam = { " + BuildUtil.toKeyValue(mParam) + " }" +
            ", \nheaders = { " + (mHBuilder == null ? "" : mHBuilder.build().toString().replace("\n", ",")) + " }" +
            ", \nisAssemblyEnabled = " + mIsAssemblyEnabled +
            ", \ntag = " + mTag +
            ", \ncacheControl = " + mCacheControl +
            " }";
    }
}
