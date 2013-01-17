package rtkaczyk.eris.service.bluetooth

import scala.actors.Actor
import scala.collection.JavaConversions._
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.service.Msg
import rtkaczyk.eris.service.ErisService
import rtkaczyk.eris.api.Events._
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.service.Config
import scala.util.control.Exception.ignoring

class DiscoveryAgent(val Service: ErisService, config: Config) extends Actor with Common {
  
  def this(Service: ErisService) = this(Service, Config.empty)

  lazy val Receiver = Service.receiver
  lazy val Agent = this
  val bt = BluetoothAdapter.getDefaultAdapter
  if (bt == null) Log.wtf(TAG, "Couldn't get Bluetooth Adapter")
  configure(config)
      
  
  private var foundDevices = List[BluetoothDevice]()
  private var waitingForConnection = false

  private object Cfg {
    var auto = true
    var full = false
    var reName = ".*"
    var accept = Seq[String]()
    var reject = Seq[String]()
  }
  
  val discoveryReceiver = new BroadcastReceiver {
    override def onReceive(context: Context, intent: Intent) {

      intent.getAction match {
        case ACTION_FOUND => {
          val device: BluetoothDevice = intent getParcelableExtra BluetoothDevice.EXTRA_DEVICE
          Log.d(TAG, "Device discovered: [%s]" format DeviceId(device))
          Agent ! Msg.DeviceFound(device)
        }
        
        case ACTION_DISCOVERY_STARTED => {
          Log.i(TAG, "Discovery started")
          Service !! DiscoveryStarted
        }
        
        case ACTION_DISCOVERY_FINISHED => {
          Agent ! Msg.DiscoveryFinished
          //Service !! DiscoveryFinished
        }
        
        case _ =>
      }
    }

    def init {
      val filter = new IntentFilter
      filter addAction ACTION_FOUND
      filter addAction ACTION_DISCOVERY_STARTED
      filter addAction ACTION_DISCOVERY_FINISHED
      Service registerReceiver (this, filter)
    }
    
    def close {
      ignoring(classOf[Exception]) {
        Service unregisterReceiver this
      }
    }
  }

  

  def act() {
    Log.d(TAG, "DiscoveryAgent started")
    discoveryReceiver.init
    if (Cfg.auto)
      this ! Msg.StartDiscovery
    
    while (true) {
      receive {
        case Msg.Reconfigure(conf) =>
          onReconfigure(conf)
        
        case Msg.StartDiscovery => 
          onStartDiscovery

        case Msg.StopDiscovery => 
          onStopDiscovery

        case Msg.DeviceFound(device) => 
          onDeviceFound(device)

        case Msg.DiscoveryFinished => 
          onDiscoveryFinished

        case Msg.AllConnectionsFinished =>
          onAllConnectionsFinished

        case Msg.Kill => 
          onKill
        
        case _ =>
      }
    }
  }
  
  def configure(conf: Config) {
    Cfg.auto = conf.getBool("auto", true)
    Cfg.full = conf.getBool("full", false)
    DeviceCache.ignorePeriod = conf.get("ignore-period", Config.nonNegativeInt, 300)
    Cfg.reName = conf.getString("restrict-name", ".*")
    Cfg.accept = conf.getSeq("accept", Config.bluetoothAddress)
    Cfg.reject = conf.getSeq("reject", Config.bluetoothAddress)
    
  }
  
  private def onReconfigure(conf: Config) {
    Log.d(TAG, "onReconfigure")
    configure(conf)
    if (Cfg.auto)
      this ! Msg.StartDiscovery
  }
  
  private def onStartDiscovery {
    Log.d(TAG, "onStartDiscovery")
    if (bt.isDiscovering || Receiver.connectionInProgress || waitingForConnection) {
      Log.w(TAG, "Can't start discovery. Adapter in use")
      Service !! DiscoveryRefused
    } else {
      foundDevices = Nil
      bt.startDiscovery
    }
  }
  
  private def onStopDiscovery {
    Log.d(TAG, "onStopDiscovery")
    if (bt.isDiscovering) {
      bt.cancelDiscovery
      this ! Msg.DiscoveryFinished
    }
  }
  
  private def onDeviceFound(device: BluetoothDevice) {
    Log.d(TAG, "onDeviceFound")
    if (validDevice(device)) {
      Log.i(TAG, "Found device [%s]" format device.getName)
      
      DeviceCache add device
      
      if (Cfg.full)
        foundDevices ::= device
      else {
        onStopDiscovery
        Log.d(TAG, "Sending %d devices to Receiver" format foundDevices.size)
        Receiver ! Msg.DevicesFound(List(device))
        waitingForConnection = true
      }
      Service !! DeviceFound(DeviceId(device))
    }
  }
  
  private def onDiscoveryFinished {
    Log.i(TAG, "Discovery finished")
    Service !! DiscoveryFinished
    
    if (Cfg.full && !foundDevices.isEmpty) {
      Log.d(TAG, "Sending %d devices to Receiver" format foundDevices.size)
      Receiver ! Msg.DevicesFound(foundDevices.reverse)
      waitingForConnection = true
    }
    
    if (Cfg.auto && !Receiver.connectionInProgress && !waitingForConnection)
      this ! Msg.StartDiscovery
  }
  
  private def onAllConnectionsFinished {
    Log.d(TAG, "onAllConnectionsFinished")
    if (Cfg.auto && !Receiver.connectionInProgress) {
      waitingForConnection = false
      this ! Msg.StartDiscovery
    }
  }
  
  private def onKill {
    bt.cancelDiscovery
    discoveryReceiver.close
    Log.d(TAG, "DiscoveryAgent stopped")
    exit
  }
  
  private def validDevice(device: BluetoothDevice) = {
    val addr = device.getAddress
    val name = device.getName
    
    (name matches Cfg.reName) &&
    !(Cfg.reject contains addr) &&
    (Cfg.accept.isEmpty || (Cfg.accept contains addr)) &&
    (DeviceCache isReady device)
  }
}
