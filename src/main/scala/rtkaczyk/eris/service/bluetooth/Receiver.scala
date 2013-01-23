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
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.api.Events._
import rtkaczyk.eris.service.Config
import rtkaczyk.eris.service.SafeActor
import rtkaczyk.eris.service.networking.Connection


class Receiver(val service: ErisService) extends SafeActor {

  lazy val discovery = service.discovery
  lazy val storage = service.storage
  lazy val receiveR = this
  
  private object Cfg {
    var auto = true
    var channel = 2
    var timeout = 5000L
    var batchSize = 0
    var full = true
  }
  
  def channel = Cfg.channel
  def timeout = Cfg.timeout
  def full = Cfg.full

  private var devicesToConnect = List[BluetoothDevice]()
  private var connection = Connection()
  
  
  def act {
    Log.d(TAG, "Receiver started")
    loop {
      react {
        case Msg.Reconfigure(conf) =>
          onReconfigure(conf)
        
        case Msg.DevicesFound(devices) => 
          onDevicesFound(devices)
        
        case Msg.ReceiverContinue =>
          onContinue
          
        case Msg.ReceiverConnect(device, from, to, limit) =>
          onConnectRequest(device, from, to, limit)
          
        case Msg.ReceiverDisconnect(all) =>
          onDisconnectRequest(all)
        
        case Msg.Kill => 
          onKill
      }
    }
  }
  
  def inProgress = connection.inProgress
  
  def configure(conf: Config) {
    Cfg.auto = conf.getBool("auto", true)
    Cfg.channel = conf.get("channel", Config.channel, 2)
    Cfg.timeout = conf.get("timeout", Config.positiveLong, 5000L)
    Cfg.batchSize = conf.get("batch", Config.nonNegativeInt, 0)
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
    devicesToConnect match {
      case dev :: devs => {
        Log.d(TAG, "Next device: %s" format dev.getName)
        devicesToConnect = devs
        val request = BtConnection.Request(from = DeviceCache getFrom dev, batch = Cfg.batchSize)
        connection = Connection(this, dev, request)
        connection.start
      }
      case Nil => {
        Log.d(TAG, "allConnectionsFinished")
        discovery ! Msg.AllConnectionsFinished
      }
    }  
  }
  
  private def onConnectRequest(device: DeviceId, from: Long, to: Long, limit: Int) {
    Log.d(TAG, "onConnectRequest")
    DeviceCache getDevice device match {
      case Some(dev) => {
        if (!connection.inProgress) {
          Log.i(TAG, "Accepted connection request (%s, %d, %d, %d)" 
              format (device, from, to, limit))
          val request = BtConnection.Request(from, to, limit, Cfg.batchSize)
          connection = Connection(this, dev, request)
        } else {
          Log.e(TAG, "Connection request rejected: adapter busy")
          service !! ReceivingFailed(device, "Adapter busy")
        }
      }
      
      case None => {
        Log.e(TAG, "Connection request rejected: Invalid device [%s]" format device)
        service !! ReceivingFailed(device, "Invalid device")
      }
    }
  }
  
  private def onDisconnectRequest(all: Boolean) {
    Log.d(TAG, "onDisconnectRequest")
    connection.cancel
    if (all)
      devicesToConnect = Nil
    else
      receiveR ! Msg.ReceiverContinue
  }
  
  private def onKill {
    connection.cancel
    Log.d(TAG, "Receiver stopped")
    exit
  }
}