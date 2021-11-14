package io.github.vigoo.zioaws.netty

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.{HttpClient, Protocol}
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{
  Protocol => AwsProtocol,
  TlsKeyManagersProvider,
  TlsTrustManagersProvider
}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.{
  Http2Configuration,
  NettyNioAsyncHttpClient,
  ProxyConfiguration => AwsProxyConfiguration
}
import zio.{Has, ZIO, ZLayer, ZManaged}

object NettyHttpClient {
  val default: ZLayer[Any, Throwable, Has[HttpClient]] =
    customized(Protocol.Http11, identity)

  val dual: ZLayer[Any, Throwable, Has[HttpClient]] =
    customized(Protocol.Dual, identity)

  def customized(
      protocol: Protocol,
      customization: NettyNioAsyncHttpClient.Builder => NettyNioAsyncHttpClient.Builder =
        identity
  ): ZLayer[Any, Throwable, Has[HttpClient]] = {
    def create(
        awsProtocol: AwsProtocol
    ): ZManaged[Any, Throwable, SdkAsyncHttpClient] =
      ZManaged
        .fromAutoCloseable(
          ZIO.attempt(
            customization(
              NettyNioAsyncHttpClient
                .builder()
            )
              .protocol(awsProtocol)
              .build()
          )
        )

    HttpClient.fromManagedPerProtocol(
      create(AwsProtocol.HTTP1_1),
      create(AwsProtocol.HTTP2)
    )(protocol)
  }

  def configured(
      tlsKeyManagersProvider: Option[TlsKeyManagersProvider] = None,
      tlsTrustManagersProvider: Option[TlsTrustManagersProvider] = None
  ): ZLayer[Has[NettyClientConfig], Throwable, Has[HttpClient]] = {
    def create(
        awsProtocol: AwsProtocol
    ): ZManaged[Has[NettyClientConfig], Throwable, SdkAsyncHttpClient] =
      ZManaged
        .fromAutoCloseable(ZIO.service[NettyClientConfig].flatMap { config =>
          ZIO.attempt {
            val builderHelper: BuilderHelper[NettyNioAsyncHttpClient] =
              BuilderHelper.apply
            import builderHelper._

            val builder0: NettyNioAsyncHttpClient.Builder =
              NettyNioAsyncHttpClient
                .builder()
                .protocol(awsProtocol)
                .maxConcurrency(config.maxConcurrency)
                .maxPendingConnectionAcquires(
                  config.maxPendingConnectionAcquires
                )
                .readTimeout(config.readTimeout)
                .writeTimeout(config.writeTimeout)
                .connectionTimeout(config.connectionTimeout)
                .connectionAcquisitionTimeout(
                  config.connectionAcquisitionTimeout
                )
                .connectionTimeToLive(config.connectionTimeToLive)
                .connectionMaxIdleTime(config.connectionMaxIdleTime)
                .useIdleConnectionReaper(config.useIdleConnectionReaper)
                .optionallyWith(config.sslProvider)(_.sslProvider)
                .optionallyWith(config.proxyConfiguration)(builder =>
                  proxy =>
                    builder.proxyConfiguration(
                      AwsProxyConfiguration
                        .builder()
                        .host(proxy.host)
                        .port(proxy.port)
                        .scheme(proxy.scheme.asString)
                        .nonProxyHosts(proxy.nonProxyHosts.asJava)
                        .build()
                    )
                )
                .optionallyWith(config.http2)(builder =>
                  http2 =>
                    builder.http2Configuration(
                      Http2Configuration
                        .builder()
                        .healthCheckPingPeriod(http2.healthCheckPingPeriod)
                        .maxStreams(http2.maxStreams)
                        .initialWindowSize(http2.initialWindowSize)
                        .build()
                    )
                )
                .optionallyWith(tlsKeyManagersProvider)(
                  _.tlsKeyManagersProvider
                )
                .optionallyWith(tlsTrustManagersProvider)(
                  _.tlsTrustManagersProvider
                )

            val builder1 =
              config.channelOptions.options.foldLeft(builder0) {
                case (b, opt) =>
                  b.putChannelOption(opt.key, opt.value)
              }

            builder1.build()
          }
        })

    ZLayer.fromManaged {
      ZManaged.service[NettyClientConfig].flatMap { config =>
        HttpClient.fromManagedPerProtocolManaged(
          create(AwsProtocol.HTTP1_1),
          create(AwsProtocol.HTTP2)
        )(config.protocol)
      }
    }
  }

}