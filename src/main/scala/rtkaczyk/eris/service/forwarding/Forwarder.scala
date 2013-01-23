package rtkaczyk.eris.service.forwarding

import rtkaczyk.eris.service.SafeActor
import rtkaczyk.eris.service.ErisService
import rtkaczyk.eris.service.Config
import rtkaczyk.eris.service.Msg
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.content.IntentFilter
import scala.util.control.Exception.ignoring
import scala.actors.Actor.actor
import scala.actors.TIMEOUT
import rtkaczyk.eris.service.networking.Connection
import android.util.Log



class Forwarder(val service: ErisService) extends SafeActor {
  lazy val storage = service.storage
  lazy val forwarder = this
  
  val network = new BroadcastReceiver {
    val manager = (service getSystemService Context.CONNECTIVITY_SERVICE).
        asInstanceOf[ConnectivityManager]
    private var wasAvailable = false
    
    def available = 
      if (manager != null) { 
        val info = manager.getActiveNetworkInfo
        if (info != null)
          info.isConnected
          else
            false
      } else false
    
    override def onReceive(context: Context, intent: Intent) {
      val itsTime = now - lastAttempt > scheduling.interval
      if (available && !wasAvailable && itsTime)
        forwarder ! Msg.ForwardPackets(false)
        
      wasAvailable = available
    }
    
    def init {
      wasAvailable = available
      val filter = new IntentFilter
      filter addAction ConnectivityManager.CONNECTIVITY_ACTION
      service registerReceiver (this, filter)
    }
    
    def close {
      ignoring(classOf[Exception]) {
        service unregisterReceiver this
      }
    }
  }
  
  class Scheduler(val interval: Long) extends SafeActor {
    def act {
      reactWithin(interval) {
        case TIMEOUT =>
          forwarder ! Msg.ForwardPackets(false)
        case Msg.CancelScheduler =>
      }
    }
  }
  
  private var scheduling: Scheduler = new Scheduler(Cfg.sInterval) 
  private var lastAttempt = 0L
  
  private var connection = Connection()
  
  private object Cfg {
    var auto = true
    var sInterval = 30000L
    var fInterval = 5000L
    var withTimestamp = true
    var batchSize = 100
    var timeout = 10000
    var addresses = Seq[(String, Int)]()
  }
  
  def withTimestamp = Cfg.withTimestamp
  def batchSize = Cfg.batchSize
  def timeout = Cfg.timeout
  def addresses = Cfg.addresses
  
  def act {
    network.init
    loop {
      react {
        case Msg.Reconfigure(conf) =>
          onReconfigure(conf)
        
        case Msg.ForwardPackets(forced) =>
          onForwardPackets(forced)
        
        case m @ Msg.ForwardingPrepared(_, _) =>
          connection ! m
          
        case m @ Msg.PacketsToForward(_) =>
          connection ! m
          
        case Msg.ForwardingFinished(success) =>
          onForwardingFinished(success)
          
        case Msg.CancelForwarding =>
          onCancelForwarding
          
        case Msg.Kill =>
          onKill
      }
    }
  }
  
  def inProgress = connection.inProgress
  
  def configure(conf: Config) {
    Cfg.auto = conf.getBool("auto", true)
    Cfg.sInterval = conf.get("success-interval", Config.positiveLong, 30L) * 1000L
    Cfg.fInterval = conf.get("failure-interval", Config.positiveLong, 5L) * 1000L
    Cfg.withTimestamp = conf.getBool("with-timestamp", true)
    Cfg.batchSize = conf.get("batch", Config.positiveInt, 100)
    Cfg.timeout = conf.get("timeout", Config.positiveInt, 10000)
    Cfg.addresses = conf.getSeq("address", Config.inetAddress)
  }
  
  private def onReconfigure(conf: Config) {
    Log.d(TAG, "onReconfigure")
    configure(conf)
    if (Cfg.auto)
      forwarder ! Msg.ForwardPackets(false)
  }
  
  private def onForwardPackets(forced: Boolean) {
    Log.d(TAG, "onForwardPackets")
    if (forced) {
      if (!connection.inProgress) {
        scheduling ! Msg.CancelScheduler
        connection = Connection(forwarder).start
      } 
    }
    else {
      val itsTime = now - lastAttempt > scheduling.interval
      if (itsTime && !connection.inProgress && network.available) {
        scheduling ! Msg.CancelScheduler
        connection = Connection(forwarder).start
      } else {
        if (!connection.inProgress && network.available) {
          val t = scheduling.interval - (now - lastAttempt)
          schedule(if (t > 0) t else 1)
        }
      }
    }
  }
  
  private def onForwardingFinished(success: Boolean) {
    Log.d(TAG, "onForwardingFinished")
    lastAttempt = now
    schedule(if (success) Cfg.sInterval else Cfg.fInterval)
  }
  
  private def onCancelForwarding {
    connection.cancel
  }
  
  private def onKill {
    network.close
    connection.cancel
    scheduling ! Msg.CancelScheduler
    exit
  }
  
  private def schedule(interval: Long) {
    scheduling = new Scheduler(interval)
    if (Cfg.auto) {
      scheduling.start
    }
  }
}