package controllers

import email.MailClient
import kong.Kong
import kong.Kong.{ ConflictFailure, GenericFailure }
import logic.DeveloperFormLogic
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc._
import store.DB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeveloperForm(dynamo: DB, kong: Kong, awsEmail: MailClient, val messagesApi: MessagesApi) extends Controller with I18nSupport {
  import DeveloperForm._
  import Forms.DeveloperCreateKeyFormData

  private val logic = new DeveloperFormLogic(dynamo, kong)

  def createKeyPage = Action { implicit request =>
    Ok(views.html.developerCreateKey(createKeyForm))
  }

  def createKey = Action.async { implicit request =>
    def handleInvalidForm(form: Form[DeveloperCreateKeyFormData]): Future[Result] = {
      Future.successful(BadRequest(views.html.developerCreateKey(form, error = Some("Please correct the highlighted fields."))))
    }

    def handleValidForm(formData: DeveloperCreateKeyFormData): Future[Result] = {
      logic.createUser(formData) flatMap { consumerKey =>
        awsEmail.sendEmailNewKey(formData.email, consumerKey) map {
          result => Redirect(routes.DeveloperForm.complete)
        } recover {
          case _ => Redirect(routes.DeveloperForm.complete).flashing("error" -> s"We were unable to send the email with the new key to ${formData.email}.  Please contact content.delivery@theguardian.com for further instructions.")
        }
      } recover {
        case ConflictFailure(errorMessage) => Conflict(views.html.developerCreateKey(createKeyForm.fill(formData), error = Some(errorMessage)))
        case GenericFailure(errorMessage) => InternalServerError(views.html.developerCreateKey(createKeyForm.fill(formData), error = Some(errorMessage)))
      }
    }
    createKeyForm.bindFromRequest.fold[Future[Result]](handleInvalidForm, handleValidForm)
  }

  def complete = Action {
    Ok(views.html.developerRegisterComplete())
  }
}

object DeveloperForm {
  import Forms.DeveloperCreateKeyFormData

  val createKeyForm: Form[DeveloperCreateKeyFormData] = Form(
    mapping(
      "name" -> nonEmptyText(maxLength = 200),
      "email" -> email.verifying("Maximum length is 200", _.length <= 200),
      "productName" -> nonEmptyText(maxLength = 200),
      "productUrl" -> optional(text.verifying("Maximum length is 200", _.length <= 200)),
      "companyName" -> optional(text.verifying("Maximum length is 200", _.length <= 200)),
      "companyUrl" -> optional(text.verifying("Maximum length is 200", _.length <= 200)),
      "acceptTerms" -> boolean.verifying("You have to accept the Guardian Open Platform terms and conditions.", terms => terms)
    )(DeveloperCreateKeyFormData.apply)(DeveloperCreateKeyFormData.unapply)
  )
}
