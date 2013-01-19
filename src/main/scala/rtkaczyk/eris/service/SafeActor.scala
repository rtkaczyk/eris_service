package rtkaczyk.eris.service

import scala.actors.Actor
import rtkaczyk.eris.api.Common
import android.util.Log

trait SafeActor extends Actor with Common {
  override def loop(body: => Unit): Unit = {
    super.loop {
      try {
        body
      } catch {
        case e: NullPointerException =>
          Log.w(TAG, "Caught %s while reacting" format e)
      }
    }
  }
}