package rtkaczyk.eris.service

import scala.xml.NodeSeq
import scala.xml.XML
import android.util.Log
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.DeviceId

trait Config extends Common {
  def node: NodeSeq
  
  def get[T](name: String, cast: String => T, default: => T): T = {
    val result: Option[T] = 
      if (has(name))
        doCast(cast, name)
      else if (has(attr(name)))
        doCast(cast, attr(name))
      else 
        None

    result getOrElse default
  }
  
  def getSeq[T](name: String, cast: String => T): Seq[T] = 
    for {
      elem <- node \ name
      value <- doCastSeq(cast, elem, name)
    } yield value
  
  def getSub(name: String) = 
    new SubConfig(node \ name)
  
  def getString(name: String, default: String = "") = 
    get(name, identity, default)
    
  def getBool(name: String, default: Boolean) =
    get(name, Config.bool, default)
  
  private def doCast[T](cast: String => T, name: String): Option[T] = 
    try {
      Some(cast((node \ name).text))
    } catch {
      case e: Exception => {
        Log.w(TAG, "Couldn't cast value: " + e.toString)
        Log.w(TAG, "Using default value for [%s]" format name)
        None
      }
    }
    
  private def doCastSeq[T](cast: String => T, elem: NodeSeq, name: String): Option[T] = 
    try {
      Some(cast(elem.text))
    } catch {
      case e: Exception => {
        Log.w(TAG, "Couldn't cast value for [%s]: [%s" format (name, e.toString))
        None
      }
    }
  
  private def has(name: String) = !(node \ name).isEmpty
  private def attr(name: String) = "@" + name
}

object Config extends Config {
  private var _node = NodeSeq.Empty
  def node = _node
  def xml_=(xml: String) {
    try {
      Log.d(TAG, "Setting new configuration:\n%s" format xml)
      _node = 
        if (xml == null || xml == "") 
          NodeSeq.Empty
        else 
          XML loadString xml
    } catch {
      case e: Exception => {
        Log.w(TAG, "Invalid configuration XML: " + e.toString)
      } 
    }
  }
  def xml = node.toString
  
  def empty = new SubConfig(NodeSeq.Empty)
  
  val nonNegativeInt = (s: String) => {
    val i = s.toInt
    require(i >= 0, "Non-negative Int required. Was [%s]" format s)
    i
  }
  
  val positiveInt = (s: String) => {
    val i = s.toInt
    require(i > 0, "Positive Int required. Was [%s]" format s)
    i
  }
  
  val positiveLong = (s: String) => {
    val i = s.toLong
    require(i > 0, "Positive Long required. Was [%s]" format s)
    i
  }
  
  val channel = (s: String) => {
    val c = s.toInt
    require(1 to 30 contains c, 
        "RFCOMM channel should be in range [1, 30]. Was [%s]" format s)
    c
  }
  
  val bool = (s: String) => s.toBoolean
  
  val bluetoothAddress = (s: String) => {
    val id = DeviceId(s)
    require(id.isValid, "Invalid bluetooth address [%s]" format s)
    id.address
  }
  
  val inetAddress = (s: String) => {
    val str = s.trim
    val (addr, port) = str splitAt (str lastIndexOf ':')
    val p = port.drop(1).toInt
    require(1 to 65535 contains p, "Invalid internet address [%s]" format s)
    val a = if ((addr startsWith "[") && (addr endsWith "]")) 
      addr.stripPrefix("[").stripSuffix("]") else addr
      
    (a, p)
  }
  
  val positivePercent = (s: String) => {
    val p = s.toDouble
    require((p > 0.0) && (p <= 100.0), "Required positive percent. Was [%s]" format s)
    p
  }
}

class SubConfig(val node: NodeSeq) extends Config
