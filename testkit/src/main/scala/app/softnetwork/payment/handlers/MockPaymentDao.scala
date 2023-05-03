package app.softnetwork.payment.handlers

import org.slf4j.{Logger, LoggerFactory}

object MockPaymentDao extends GenericPaymentDao with MockPaymentHandler {
  lazy val log: Logger = LoggerFactory getLogger getClass.getName
}
