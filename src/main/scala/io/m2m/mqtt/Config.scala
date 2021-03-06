/*
 *  Copyright 2015 2lemetry, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */


package io.m2m.mqtt

import com.typesafe.config.{ConfigValue, ConfigFactory, Config => TSConfig}
import scala.reflect.io.Streamable
import java.io.FileInputStream
import scala.util.{Random, Try}
import scala.collection.JavaConversions._
import java.util.UUID

case class SplunkConfig(splunkUser: String, splunkPass: String, splunkUrl: String, splunkProject: String)

object Config {
  val configFactory = ConfigFactory.load()

  val splunkConf = configFactory.getBoolean("splunk.enabled") match {
    case true =>
      val splunkUser = configFactory.getString("splunk.user")
      val splunkPass = configFactory.getString("splunk.password")
      val splunkUrl = configFactory.getString("splunk.url")
      val splunkProject = configFactory.getString("splunk.project")
      Option(SplunkConfig(splunkUser, splunkPass, splunkUrl, splunkProject))
    case false => None
  }
  

  def payload(config: TSConfig): MessageSource = {
    def getFile(cfg: TSConfig) = FileMessage(cfg.getString("file"))
    def getUTF(cfg: TSConfig) = Utf8Message(cfg.getString("text"))
    def getGenerated(cfg: TSConfig) = GeneratedMessage(cfg.getInt("size"))
    def getSamples(cfg: TSConfig) = SampledMessages.fromRaw {
      cfg.getConfigList("samples")
        .map(x => Try(x.getDouble("percent")).toOption.map(_ / 100) -> payload(x))
        .toList
    }

    val fm = Try(getFile(config))
    val utf = fm.orElse(Try(getUTF(config)))
    val gen = utf.orElse(Try(getGenerated(config)))
    val samples = gen.orElse(Try(getSamples(config)))

    samples.getOrElse(GeneratedMessage(1024))
  }

  def getConfig(conf: TSConfig): Config = {
    Config(
      conf.getString("host"),
      conf.getInt("port"),
      if (conf.hasPath("username")) Some(conf.getString("username")) else None,
      if (conf.hasPath("password")) Some(conf.getString("password")) else None,
      PublisherConfig(
        conf.getString("publishers.topic"),
        conf.getInt("publishers.count"),
        conf.getMilliseconds("publishers.millis-between-publish"),
        payload(conf.getConfig("publishers.payload")),
        conf.getInt("publishers.qos"),
        conf.getBoolean("publishers.retain"),
        conf.getString("publishers.client-id-prefix"),
        Try(conf.getBoolean("publishers.clean-session")).getOrElse(true),
        Try(conf.getInt("publishers.time-span")).toOption,
          Try(conf.getString("subscribers.validate-file")).getOrElse("sent-messages.dat")
      ),
      SubscriberConfig(
        conf.getString("subscribers.topic"),
        conf.getInt("subscribers.count"),
        conf.getInt("subscribers.qos"),
        conf.getString("subscribers.client-id-prefix"),
        Try(conf.getBoolean("subscribers.clean-session")).getOrElse(true),
        conf.getBoolean("subscribers.shared"),
        if (conf.hasPath("subscribers.custom-host")) Some(conf.getString("subscribers.custom-host")) else None,
        Try(conf.getInt("subscribers.time-span")).toOption,
        Try(conf.getString("subscribers.validate-file")).getOrElse("received-messages.dat")
      ),
      conf.getMilliseconds("millis-between-connects"),
      conf.getString("queue-monitor.clientid"),
      conf.getString("queue-monitor.topic"),
      Try(conf.getBoolean("pwNeedsHashing")).getOrElse(true),
      Try(conf.getBoolean("trace-latency")).getOrElse(false)
    )
  }
  
  lazy val config = getConfig(configFactory)
}

case class PublisherConfig(topic: String, count: Int, rate: Long, payload: MessageSource, qos: Int, retain: Boolean,
                            idPrefix: String, cleanSession: Boolean, timeSpan: Option[Int], validateFile: String)

case class SubscriberConfig(topic: String, count: Int, qos: Int, clientIdPrefix: String, clean: Boolean, shared: Boolean,
                             host: Option[String], timeSpan: Option[Int], validateFile: String)

case class Config(host: String, port: Int, user: Option[String], password: Option[String], publishers: PublisherConfig,
                  subscribers: SubscriberConfig, connectRate: Long, queueClientid: String, queueTopic: String,
                  pwNeedsHashing: Boolean, traceLatency: Boolean) {


  private def templateTopic(topic: String, id: Int) = topic.replaceAll("\\$num", id.toString)

  def pubTopic(id: Int, msgId: Option[UUID]): String = {
    val msgIdSegment = msgId.map("/" + _.toString.replace("-", "")).getOrElse("")
    val topic = templateTopic(publishers.topic, id) + msgIdSegment
    topic
  }
  def subTopic(id: Int): String =  {
    val msgSegment =
      if (traceLatency && !subscribers.topic.endsWith("#"))
        "/+"
      else
        ""
    templateTopic(subscribers.topic, id) + msgSegment
  }
  def subscriberId(id: Int) = subscribers.clientIdPrefix + id
  def publisherId(id: Int) = publishers.idPrefix + id

  /**
   * The password that is sent over the wire. If hashPassword is set to true, it md5's password
   * @return
   */
  def wirePassword =
    if (!pwNeedsHashing)
      password
    else
      password.map(Client.md5)
}

sealed abstract class MessageSource {
  def get(clientNum: Int, iteration: Int): Array[Byte]
}

case class Utf8Message(msg: String) extends MessageSource {
  def get(clientNum: Int, iteration: Int) = msg.getBytes("utf8")
}

case class FileMessage(file: String) extends MessageSource {
  lazy val fileBytes = Streamable.bytes(new FileInputStream(file))
  def get(clientNum: Int, iteration: Int) = fileBytes
}

case class GeneratedMessage(size: Int) extends MessageSource {
  lazy val msg: Array[Byte] = {
    val bytes = new Array[Byte](size)
    Random.nextBytes(bytes)
    bytes.map(b => ((b % 70) + 30).toByte)
  }

  def get(clientNum: Int, iteration: Int) = msg
}

case class Sample(msg: MessageSource, lower: Double, upper: Double)
case class SampledMessages(samples: List[Sample]) extends MessageSource {
  def getMessage(n: Double) = samples
    .find(m => m.lower <= n && n < m.upper)
    .map(_.msg)
    .getOrElse(GeneratedMessage(1024))

  def get(clientNum: Int, iteration: Int) = {
    val n = Random.nextDouble()
    getMessage(n).get(clientNum, iteration)
  }
}

object SampledMessages {
  def fromRaw(messages: List[(Option[Double], MessageSource)]): SampledMessages = {
    val (base, explicitSamples) = messages.filter(_._1.isDefined).foldLeft(0.0 -> List[Sample]()) {
      case ((low, acc), (percent, msg)) =>
        val hi = low + percent.get
        hi -> (Sample(msg, low, hi) :: acc)
    }

    val remaining = messages.filter(!_._1.isDefined)
    val remainingPercent = 1 - base
    val size = remainingPercent / remaining.size
    
    val remainingSamples = remaining.map(_._2).zipWithIndex
      .map{ case (msg, i) => Sample(msg, base + (size*i), base + (size*i) + size)}

    SampledMessages(explicitSamples ++ remainingSamples)
  }
}

