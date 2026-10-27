package scheduler.modules.consultation.adapters

import play.api.libs.json.*
import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.consultation.ConsultationJson.given
import scheduler.modules.consultation.ConsultationStatus
import scheduler.modules.consultation.application.{ConsultationService, Review, SubmitRequest}
import scheduler.modules.identity.Role.*
import scheduler.shared.Validate

final class ConsultationController(cc: ControllerComponents, guard: Guard, consultations: ConsultationService) extends ApiController(cc):

  private given Reads[Option[ConsultationStatus]] = (__ \ "status").readNullable(Validate.oneOf(ConsultationStatus.values))
  private val scope: Reads[Option[String]] = (__ \ "dealershipId").readNullable(Validate.dealershipScope)

  def submit: Action[AnyContent] = guard(CUSTOMER).async { req =>
    json(Created)(consultations.submit(req.auth, body[SubmitRequest](req), req.log))
  }

  def listMine: Action[AnyContent] = guard(CUSTOMER).async(req => json()(consultations.listMine(req.auth)))

  def get(id: String): Action[AnyContent] = guard(CUSTOMER, SERVICE_ADVISOR, ADMIN, TECHNICIAN).async { req =>
    json()(consultations.getVisible(req.auth, uuid(id)))
  }

  def cancel(id: String): Action[AnyContent] = guard(CUSTOMER).async { req =>
    json()(consultations.cancel(req.auth, uuid(id), req.log))
  }

  def listForDealership: Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN, TECHNICIAN).async { req =>
    json()(consultations.listForDealership(req.auth, query[Option[ConsultationStatus]](req), query[Option[String]](req)(using scope)))
  }

  def review(id: String): Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN, TECHNICIAN).async { req =>
    json()(consultations.review(req.auth, uuid(id), body[Review](req), req.log))
  }
