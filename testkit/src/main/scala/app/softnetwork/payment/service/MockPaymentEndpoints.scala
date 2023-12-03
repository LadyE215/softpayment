package app.softnetwork.payment.service

import app.softnetwork.payment.handlers.MockPaymentHandler
import app.softnetwork.session.service.SessionMaterials
import org.softnetwork.session.model.JwtClaims

trait MockPaymentEndpoints extends MangoPayPaymentEndpoints with MockPaymentHandler {
  _: SessionMaterials[JwtClaims] =>
}
