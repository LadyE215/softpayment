package app.softnetwork.payment.api

import akka.actor.typed.ActorSystem
import app.softnetwork.payment.handlers.{
  MockPaymentHandler,
  MockPaymentTypeKey,
  MockSoftPaymentAccountDao,
  SoftPaymentAccountDao
}
import org.slf4j.{Logger, LoggerFactory}

trait MockPaymentServer extends PaymentServer with MockPaymentHandler

object MockPaymentServer {
  def apply(sys: ActorSystem[_]): MockPaymentServer = {
    new MockPaymentServer {
      lazy val log: Logger = LoggerFactory getLogger getClass.getName
      override implicit val system: ActorSystem[_] = sys
    }
  }
}
