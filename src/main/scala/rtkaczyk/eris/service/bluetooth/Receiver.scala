package rtkaczyk.eris.service.bluetooth

import java.io.IOException
import scala.actors.Actor
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.service.Msg
import rtkaczyk.eris.service.ErisService
import rtkaczyk.eris.api.Packet
import scala.util.control.Exception.ignoring
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.api.Events._
import rtkaczyk.eris.service.Config


class Receiver(val Service: ErisService, config: Config) extends Actor with Common {

  def this(Service: ErisService) = this(Service, Config.empty)
  
  lazy val Agent = Service.discoveryAgent
  lazy val Storage = Service.storage
  lazy val Receiver = this
  configure(config)
  
  
  
  private object Cfg {
    var auto = true
    var channel = 2
    var batchSize = 0
    var full = true
  }

  private var devicesToConnect = List[BluetoothDevice]()
  private var connection = Connection()
  private var connectionCanceled = false
  
  
  
  def act() {
    Log.d(TAG, "Receiver started")
    while (true) {
      receive {
        case Msg.Reconfigure(conf) =>
          onReconfigure(conf)
        
        case Msg.DevicesFound(devices) => 
          onDevicesFound(devices)
        
        case Msg.ReceiverContinue =>
          onContinue
          
        case Msg.ReceiverConnect(device, from, to, limit) =>
          onConnectRequest(device, from, to, limit)
          
        case Msg.ReceiverDisconnect =>
          onDisconnectRequest
        
        case Msg.Kill => 
          onKill
          
        case _ =>
      }
    }
  }
  
  def connectionInProgress = connection.inProgress
  
  def configure(conf: Config) {
    Cfg.auto = conf.getBool("auto", true)
    Cfg.channel = conf.get("channel", Config.channel, 2)
    Cfg.batchSize = conf.get("batch-size", Config.nonNegativeInt, 0)
    Cfg.full = conf.getBool("full", true)
  }
  
  private def onReconfigure(conf: Config) = {
    Log.d(TAG, "onReconfigure")
    configure(conf)
  }
  
  private def onDevicesFound(devices: List[BluetoothDevice]) {
    Log.d(TAG, "onDevicesFound")
    if (Cfg.auto) {
      devicesToConnect = devices
      onContinue
    }
  }
  
  private def onContinue {
    Log.d(TAG, "onContinue, devicesToConnect: %d" format devicesToConnect.size)
    if (connection.inProgress) {
      Log.d(TAG, "Connection in progress -> continue")
      connection.continue
    } 
    else devicesToConnect match {
      case dev :: devs => {
        Log.d(TAG, "Next device: %s" format dev.getName)
        devicesToConnect = devs
        val request = Request(from = DeviceCache getFrom dev, batch = Cfg.batchSize)
        connection = Connection(dev, request)
        connection.start
      }
      case Nil => {
        Log.d(TAG, "allConnectionsFinished")
        Agent ! Msg.AllConnectionsFinished
      }
    }  
      
      
//      if (foundDevices.hasNext) {
//        Log.d(TAG, "foundDevices hasNext")
//        val device = foundDevices.next
//        val request = Request(from = DeviceCache getFrom device, batch = Cfg.batchSize)
//        connection = Connection(device, request)
//      } else {
//        Log.d(TAG, "allConnectionsFinished")
//        Agent ! Msg.AllConnectionsFinished
//      }
  }
  
  private def onConnectRequest(device: DeviceId, from: Long, to: Long, limit: Int) {
    Log.d(TAG, "onConnectRequest")
    DeviceCache getDevice device match {
      case Some(dev) => {
        if (!connection.inProgress) {
          Log.i(TAG, "Accepted connection request (%s, %d, %d, %d)" 
              format (device, from, to, limit))
          val request = Request(from, to, limit, Cfg.batchSize)
          connection = new RealConnection(dev, request)
        } else {
          Log.e(TAG, "Connection request rejected: adapter busy")
          Service !! ConnectionFailed(device, "Adapter busy")
        }
      }
      
      case None => {
        Log.e(TAG, "Connection request rejected: Invalid device [%s]" format device)
        Service !! ConnectionFailed(device, "Invalid device")
      }
    }
  }
  
  private def onDisconnectRequest {
    Log.d(TAG, "onDisconnectRequest")
    if (connection.inProgress) {
      Log.d(TAG, "Disconnecting on request")
      connection.finish
      Receiver ! Msg.ReceiverContinue
    }
  }
  
  private def onKill {
    if (connection.inProgress)
      connection.finish
    Log.d(TAG, "Receiver stopped")
    exit
  }
  
  
  
  private case class Request (
    from: Long = 0,
    to: Long = 0,
    limit: Int = 0,
    batch: Int = 0
  )
  
  private trait Connection {
    def inProgress = false
    def start = {}
    def continue {}
    def finish {}
  }
  
  private object Connection {
    def apply(): Connection = new FakeConnection
    def apply(device: BluetoothDevice, req: Request): Connection =
      new RealConnection(device, req)
  }
  
  private class FakeConnection extends Connection
  
  private class RealConnection(device: BluetoothDevice, req: Request) extends Connection {
    private var socket: BluetoothSocket = null
    private var noPackets = 0
    private var proto: Protocol = null
    private var done = true
    
    override def start {
      done = false
      try {
        Log.i(TAG, "Connecting to [%s]" format DeviceId(device))
        val m = device.getClass getMethod ("createInsecureRfcommSocket", classOf[Int])
        socket = (m invoke (device, Integer valueOf Cfg.channel)).asInstanceOf[BluetoothSocket]
            socket.connect
            proto = new Protocol(socket, Cfg.full)
        
        Log.d(TAG, "Sending request")
        proto requestPackets(req.from, req.to, req.limit, req.batch)
        
      }
      catch {
        case e: IOException if (e.getMessage == "Connection refused") => {
          Log.e(TAG, "Error while connecting to Bluetooth device [%s]:\n %s" 
              format (DeviceId(device), e.toString))
              Service !! ConnectionRefused(device)
              close
        }
        case e: Exception => {
          Log.e(TAG, "Error while connecting to Bluetooth device [%s]" 
              format DeviceId(device), e)
              onError(e.toString)
        }
      } 
      finally {
        Receiver.onContinue
      }
    }
    
    override def inProgress = !done
    
    override def continue {
      Log.d(TAG, "Connection continue")
      try {
        val packets = proto.getPackets
        if (!packets.isEmpty) {
          Log.d(TAG, "Received %d packets" format packets.size)
          noPackets += packets.size
          Storage ! Msg.StorePackets(packets)
          Service !! PacketsReceived(device, noPackets, proto.allPackets)
        } else {
          val ack = proto.confirm 
          finish
        }
      }
      catch {
        case e: Exception =>
          Log.e(TAG, "Error while receiving from Bluetooth device [%s]" 
              format DeviceId(device), e)
          onError(e.toString)
          finish
      }
      finally {
        DeviceCache update (device, proto.to)
        Receiver.onContinue
      }
    }
    
    override def finish {
      close
      Log.i(TAG, "Connection to [%s] finished. Received %d packets" format 
          (DeviceId(device), noPackets))
      Service !! ConnectionFinished(device, noPackets, proto.from, proto.to)
    }
    
    private def close {
      if (!done) {
        ignoring(classOf[Exception]) {
          socket.close
        }
        done = true
      }
    }
    
    private def onError(cause: String) {
      close
      Service !! ConnectionFailed(device, cause)
    }
  } 
}