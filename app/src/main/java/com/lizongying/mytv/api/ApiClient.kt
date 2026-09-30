package com.lizongying.mytv.api


import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.lizongying.mytv.jce.JceConverterFactory
import com.lizongying.mytv.requests.ReleaseService
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.protobuf.ProtoConverterFactory
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class ApiClient {
    private val yspUrl = "https://player-api.yangshipin.cn/"
    private val myUrl = "https://lyrics.run/"
    private val protoUrl = "https://capi.yangshipin.cn/"
    private val traceUrl = "https://btrace.yangshipin.cn/"
    private val trace2Url = "https://aatc-api.yangshipin.cn/"
    private val tokenUrl = "https://h5access.yangshipin.cn/"
    private val trace3Url = "https://dtrace.ysp.cctv.cn/"
    private val jceUrl = "https://jacc.ysp.cctv.cn/"
    private val fUrl = "https://m.fengshows.com/"

    private var okHttpClient = getUnsafeOkHttpClient()
    private var gson = Gson()

    val yspApiService: YSPApiService by lazy {
        Retrofit.Builder()
            .baseUrl(yspUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPApiService::class.java)
    }

    val yspTokenService: YSPTokenService by lazy {
        Retrofit.Builder()
            .baseUrl(myUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPTokenService::class.java)
    }

    val releaseService: ReleaseService by lazy {
        Retrofit.Builder()
            .baseUrl(HOST)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(ReleaseService::class.java)
    }

    val yspProtoService: YSPProtoService by lazy {
        Retrofit.Builder()
            .baseUrl(protoUrl)
            .client(okHttpClient)
            .addConverterFactory(ProtoConverterFactory.create())
            .build().create(YSPProtoService::class.java)
    }

    val yspBtraceService: YSPBtraceService by lazy {
        Retrofit.Builder()
            .baseUrl(traceUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPBtraceService::class.java)
    }

    val yspBtraceService2: YSPBtraceService by lazy {
        Retrofit.Builder()
            .baseUrl(trace2Url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPBtraceService::class.java)
    }

    val yspToken2Service: YSPApiService by lazy {
        Retrofit.Builder()
            .baseUrl(tokenUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPApiService::class.java)
    }

    val yspBtraceService3: YSPBtraceService by lazy {
        Retrofit.Builder()
            .baseUrl(trace3Url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(YSPBtraceService::class.java)
    }

    val yspJceService: YSPJceService by lazy {
        Retrofit.Builder()
            .baseUrl(jceUrl)
            .client(okHttpClient)
            .addConverterFactory(JceConverterFactory.create())
            .build().create(YSPJceService::class.java)
    }

    val fAuthService: FAuthService by lazy {
        Retrofit.Builder()
            .baseUrl(fUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(FAuthService::class.java)
    }

    private fun enableTls12OnPreLollipop(client: OkHttpClient.Builder): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                val sc = SSLContext.getInstance("TLSv1.2")

                sc.init(null, null, null)

                // okhttp4 要求 sslSocketFactory 必须携带 X509TrustManager；
                // 这里取系统默认信任管理器（此分支仅服务 API 21 的老设备）
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as java.security.KeyStore?)
                val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
                client.sslSocketFactory(Tls12SocketFactory(sc.socketFactory), trustManager)

                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()

                val specs: MutableList<ConnectionSpec> = ArrayList()
                specs.add(cs)
                specs.add(ConnectionSpec.COMPATIBLE_TLS)
                specs.add(ConnectionSpec.CLEARTEXT)

                client.connectionSpecs(specs)
            } catch (exc: java.lang.Exception) {
                Log.e("OkHttpTLSCompat", "Error while setting TLS 1.2", exc)
            }
        }

        return client
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts: Array<TrustManager> = arrayOf(
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out java.security.cert.X509Certificate>?,
                        authType: String?
                    ) {
                    }

                    override fun checkServerTrusted(
                        chain: Array<out java.security.cert.X509Certificate>?,
                        authType: String?
                    ) {
                    }

                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                        return emptyArray()
                    }
                }
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.2.2", 8888))

            val builder = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
//                .proxy(proxy)
                .dns(DnsCache.shared)

            return enableTls12OnPreLollipop(builder).build()

        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    companion object {
        // 自升级站点：GitHub Pages（仓库 fshby/fshby.github.io.mytv，main 分支根目录部署）
        const val HOST = "https://fshby.github.io/fshby.github.io.mytv/update/"
        const val DOWNLOAD_HOST = "https://fshby.github.io/fshby.github.io.mytv/download/"
    }
}