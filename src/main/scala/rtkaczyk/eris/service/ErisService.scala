package rtkaczyk.eris.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import rtkaczyk.eris.service.bluetooth._
import rtkaczyk.eris.service.storage.Storage
import scala.actors.Actor
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.IErisApi
import rtkaczyk.eris.api.Events._
import rtkaczyk.eris.service.bluetooth.DiscoveryAgent
import rtkaczyk.eris.service.forwarding.Forwarder

class ErisService extends Service with Common {

  var _discovery: DiscoveryAgent = null
  var _receiver: Receiver = null 
  var _storage: Storage = null
  var _forwarder: Forwarder = null
  
  def discovery = _discovery
  def receiver = _receiver
  def storage = _storage
  def forwarder = _forwarder

  val api = new API(this)
  
  private var started = false

  override def onBind(intent: Intent) = {
    Log.d(TAG, "Binding service: [%s]" format intent.getAction)
    api
  }

  override def onCreate {
    super.onCreate
    Log.d(TAG, "onCreate")
  }
  
  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = {
    Log.d(TAG, "onStartCommand")
    if (!started) {
      Config.xml = intent getStringExtra "xmlConfig"
      init
      this !! ErisStarted
    }
    started = true
    Service.START_STICKY
  }

  override def onDestroy {
    Log.d(TAG, "onDestroy")

    discovery ! Msg.Kill
    receiver ! Msg.Kill
    storage ! Msg.Kill
    forwarder ! Msg.Kill
    
    started = false
    this !! ErisStopped

    super.onDestroy
  }

  def !! (event: ErisEvent) {
    sendBroadcast(event.toIntent, ErisPermission)
  }
  
  def reconfigure {
    Log.d(TAG, "Reconfiguring service")
    discovery ! Msg.Reconfigure(Config.getSub("discovery"))
    receiver ! Msg.Reconfigure(Config.getSub("receiver"))
    storage ! Msg.Reconfigure(Config.getSub("storage"))
    forwarder ! Msg.Reconfigure(Config.getSub("forwarding"))
  }  
  
  private def configure {
    Log.d(TAG, "Configuring service")
    discovery configure Config.getSub("discovery")
    receiver configure Config.getSub("receiver")
    storage configure Config.getSub("storage")
    forwarder configure Config.getSub("forwarding")
  }
  
  private def init {
    _storage = new Storage(this)
    _receiver = new Receiver(this)
    _discovery = new DiscoveryAgent(this)
    _forwarder = new Forwarder(this)
    
    configure
    
    storage.start
    receiver.start
    discovery.start
    forwarder.start
  }
}