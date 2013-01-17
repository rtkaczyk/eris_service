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
    require(i >= 0, "Non-negative Int required. Was [%d]" format i)
    i
  }
  
  val channel = (s: String) => {
    val c = s.toInt
    require(1 to 30 contains c, 
        "RFCOMM channel should be in range [1, 30]. Was [%d]" format c)
    c
  }
  
  val bool = (s: String) => s.toBoolean
  
  val bluetoothAddress = (s: String) => {
    val id = DeviceId(s)
    require(id.isValid, "Invalid bluetooth address [%s]" format s)
    id.address
  }
}

class SubConfig(val node: NodeSeq) extends Config
