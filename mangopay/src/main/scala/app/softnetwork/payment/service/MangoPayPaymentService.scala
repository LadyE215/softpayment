package app.softnetwork.payment.service

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import app.softnetwork.payment.config.PaymentSettings.HooksRoute
import app.softnetwork.payment.handlers.MangoPayPaymentHandler
import app.softnetwork.payment.message.PaymentMessages.{
  InvalidateRegularUser,
  KycDocumentStatusUpdated,
  MandateStatusUpdated,
  PaymentResult,
  RegularUserInvalidated,
  RegularUserValidated,
  UboDeclarationStatusUpdated,
  UpdateKycDocumentStatus,
  UpdateMandateStatus,
  UpdateUboDeclarationStatus,
  ValidateRegularUser
}
import app.softnetwork.payment.model.{BankAccount, KycDocument, UboDeclaration}
import app.softnetwork.session.service.SessionService
import com.mangopay.core.enumerations.EventType
import org.slf4j.{Logger, LoggerFactory}

trait MangoPayPaymentService extends GenericPaymentService with MangoPayPaymentHandler {

  def completeWithKycDocumentUpdatedResult(
    eventType: String,
    resourceId: String
  ): PaymentResult => Route = {
    case _: KycDocumentStatusUpdated =>
      logger.info(
        s"[Payment Hooks] KYC has been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
    case _ =>
      logger.warn(
        s"[Payment Hooks] KYC has not been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
  }

  def completeWithUboDeclarationStatusUpdatedResult(
    eventType: String,
    resourceId: String
  ): PaymentResult => Route = {
    case UboDeclarationStatusUpdated =>
      logger.info(
        s"[Payment Hooks] Ubo Declaration has been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
    case _ =>
      logger.warn(
        s"[Payment Hooks] Ubo Declaration has not been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
  }

  def completeWithRegularUserValidationResult(
    eventType: String,
    resourceId: String
  ): PaymentResult => Route = {
    case RegularUserValidated =>
      logger.info(
        s"[Payment Hooks] Regular User has been validated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
    case _ =>
      logger.warn(
        s"[Payment Hooks] Regular User has not been validated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
  }

  def completeWithRegularUserInvalidationResult(
    eventType: String,
    resourceId: String
  ): PaymentResult => Route = {
    case RegularUserInvalidated =>
      logger.info(
        s"[Payment Hooks] Regular User has been invalidated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
    case _ =>
      logger.warn(
        s"[Payment Hooks] Regular User has not been invalidated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
  }

  def completeWithMandateStatusUpdatedResult(
    eventType: String,
    resourceId: String
  ): PaymentResult => Route = {
    case _: MandateStatusUpdated =>
      logger.info(
        s"[Payment Hooks] Mandate has been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
    case _ =>
      logger.warn(
        s"[Payment Hooks] Mandate has not been updated for $resourceId -> $eventType"
      )
      complete(HttpResponse(StatusCodes.OK))
  }

  override lazy val hooks: Route = pathPrefix(HooksRoute) {
    pathEnd {
      parameters("EventType", "RessourceId") { (eventType, resourceId) =>
        Option(EventType.valueOf(eventType)) match {
          case Some(s) =>
            s match {
              case EventType.KYC_FAILED =>
                run(
                  UpdateKycDocumentStatus(
                    resourceId,
                    Some(KycDocument.KycDocumentStatus.KYC_DOCUMENT_REFUSED)
                  )
                ) completeWith {
                  completeWithKycDocumentUpdatedResult(eventType, resourceId)
                }
              case EventType.KYC_SUCCEEDED =>
                run(
                  UpdateKycDocumentStatus(
                    resourceId,
                    Some(KycDocument.KycDocumentStatus.KYC_DOCUMENT_VALIDATED)
                  )
                ) completeWith {
                  completeWithKycDocumentUpdatedResult(eventType, resourceId)
                }
              case EventType.KYC_OUTDATED =>
                run(
                  UpdateKycDocumentStatus(
                    resourceId,
                    Some(KycDocument.KycDocumentStatus.KYC_DOCUMENT_OUT_OF_DATE)
                  )
                ) completeWith {
                  completeWithKycDocumentUpdatedResult(eventType, resourceId)
                }
              case EventType.UBO_DECLARATION_REFUSED =>
                run(
                  UpdateUboDeclarationStatus(
                    resourceId,
                    Some(UboDeclaration.UboDeclarationStatus.UBO_DECLARATION_REFUSED)
                  )
                ) completeWith {
                  completeWithUboDeclarationStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.UBO_DECLARATION_VALIDATED =>
                run(
                  UpdateUboDeclarationStatus(
                    resourceId,
                    Some(UboDeclaration.UboDeclarationStatus.UBO_DECLARATION_VALIDATED)
                  )
                ) completeWith {
                  completeWithUboDeclarationStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.UBO_DECLARATION_INCOMPLETE =>
                run(
                  UpdateUboDeclarationStatus(
                    resourceId,
                    Some(UboDeclaration.UboDeclarationStatus.UBO_DECLARATION_INCOMPLETE)
                  )
                ) completeWith {
                  completeWithUboDeclarationStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.USER_KYC_REGULAR =>
                run(ValidateRegularUser(resourceId)) completeWith {
                  completeWithRegularUserValidationResult(eventType, resourceId)
                }
              case EventType.USER_KYC_LIGHT =>
                run(InvalidateRegularUser(resourceId)) completeWith {
                  completeWithRegularUserInvalidationResult(eventType, resourceId)
                }
              case EventType.MANDATE_FAILED =>
                run(
                  UpdateMandateStatus(resourceId, Some(BankAccount.MandateStatus.MANDATE_FAILED))
                ) completeWith {
                  completeWithMandateStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.MANDATE_SUBMITTED =>
                run(
                  UpdateMandateStatus(
                    resourceId,
                    Some(BankAccount.MandateStatus.MANDATE_SUBMITTED)
                  )
                ) completeWith {
                  completeWithMandateStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.MANDATE_CREATED =>
                run(
                  UpdateMandateStatus(resourceId, Some(BankAccount.MandateStatus.MANDATE_CREATED))
                ) completeWith {
                  completeWithMandateStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.MANDATE_EXPIRED =>
                run(
                  UpdateMandateStatus(resourceId, Some(BankAccount.MandateStatus.MANDATE_EXPIRED))
                ) completeWith {
                  completeWithMandateStatusUpdatedResult(eventType, resourceId)
                }
              case EventType.MANDATE_ACTIVATED =>
                run(
                  UpdateMandateStatus(
                    resourceId,
                    Some(BankAccount.MandateStatus.MANDATE_ACTIVATED)
                  )
                ) completeWith {
                  completeWithMandateStatusUpdatedResult(eventType, resourceId)
                }
              case _ =>
                logger.error(s"[Payment Hooks] Event $eventType for $resourceId is not supported")
                complete(HttpResponse(StatusCodes.BadRequest))
            }
          case None =>
            logger.error(s"[Payment Hooks] Event $eventType for $resourceId is not supported")
            complete(HttpResponse(StatusCodes.BadRequest))
        }
      }
    }
  }
}

object MangoPayPaymentService {
  def apply(_system: ActorSystem[_], _sessionService: SessionService): MangoPayPaymentService = {
    new MangoPayPaymentService {
      lazy val log: Logger = LoggerFactory getLogger getClass.getName
      override implicit def system: ActorSystem[_] = _system
      override def sessionService: SessionService = _sessionService
    }
  }
}
