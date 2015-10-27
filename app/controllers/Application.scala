package controllers

import models._
import com.gu.googleauth.{ UserIdentity, GoogleAuthConfig }
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc._
import store._
import kong._
import kong.Kong._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ApplicationLogic {
  import Application._

  def dynamo: DB
  def kong: Kong

  def createNewUser(form: CreateUserFormData): Future[String] = {
    def saveUserAndKeyOnDB(consumer: ConsumerCreationResult, formData: CreateUserFormData, rateLimits: RateLimits): Unit = {
      val newBonoboUser = BonoboUser(consumer.id, formData)
      dynamo.saveBonoboUser(newBonoboUser)

      // when a user is created, bonoboId and kongId (taken from the consumer object) will be the same
      saveKeyOnDB(userId = consumer.id, consumer, rateLimits, formData.tier)
    }

    def createConsumerAndKey: Future[String] = {
      val rateLimits: RateLimits = form.tier.rateLimit
      kong.createConsumerAndKey(rateLimits, form.key) map {
        consumer =>
          saveUserAndKeyOnDB(consumer, form, rateLimits)
          consumer.id
      }
    }

    checkingIfKeyAlreadyTaken(form.key)(createConsumerAndKey)
  }

  def createNewKey(userId: String, form: CreateKeyFormData): Future[Unit] = {
    def createConsumerAndKey: Future[Unit] = {
      val rateLimits: RateLimits = form.tier.rateLimit
      kong.createConsumerAndKey(rateLimits, form.key) map {
        consumer => saveKeyOnDB(userId, consumer, rateLimits, form.tier)
      }
    }

    checkingIfKeyAlreadyTaken(form.key)(createConsumerAndKey)
  }

  private def checkingIfKeyAlreadyTaken[A](key: Option[String])(f: Future[A]): Future[A] = key match {
    case Some(value) =>
      if (dynamo.retrieveKey(value).isDefined)
        Future.failed(ConflictFailure("Key already taken."))
      else f
    case None => f
  }

  private def saveKeyOnDB(userId: String, consumer: ConsumerCreationResult, rateLimits: RateLimits, tier: Tier): Unit = {
    val newKongKey = KongKey(userId, consumer, rateLimits, tier)
    dynamo.saveKongKey(newKongKey)
  }
}

class Application(val dynamo: DB, val kong: Kong, val messagesApi: MessagesApi, val authConfig: GoogleAuthConfig, val enableAuth: Boolean) extends Controller
    with ApplicationLogic
    with AuthActions
    with I18nSupport {

  import Application._

  object FakeAuthAction extends AuthenticatedBuilder[UserIdentity](userinfo = _ => Some(UserIdentity("", "", "First", "Last", Long.MaxValue, None)))

  private def maybeAuth: AuthenticatedBuilder[UserIdentity] = if (enableAuth) AuthAction else FakeAuthAction

  def showKeys(direction: String, range: Option[Long]) = maybeAuth { implicit request =>
    val resultsPage = dynamo.getKeys(direction, range)
    val totalKeys = dynamo.getNumberOfKeys
    val givenDirection = if (range.isDefined) direction else ""
    Ok(views.html.showKeys(resultsPage.items, givenDirection, resultsPage.hasNext, totalKeys, request.user.firstName, pageTitle = "All Keys"))
  }

  def search = maybeAuth { implicit request =>
    searchForm.bindFromRequest.fold(
      formWithErrors => {
        BadRequest(views.html.showKeys(List.empty, lastDirection = "", hasNext = false, totalKeys = 0, request.user.firstName, pageTitle = "Invalid search", error = Some("Try again with a valid query.")))
      },
      searchFormData => {
        val keys: List[BonoboInfo] = dynamo.search(searchFormData.query)
        val searchResultsMessage = s"Search results for query: ${searchFormData.query}"
        Ok(views.html.showKeys(keys, lastDirection = "", hasNext = false, keys.length, request.user.firstName, pageTitle = searchResultsMessage))
      }
    )
  }

  def createUserPage = maybeAuth { implicit request =>
    Ok(views.html.createUser(createUserForm, request.user.firstName, createUserPageTitle))
  }

  def createUser = maybeAuth.async { implicit request =>
    def handleInvalidForm(form: Form[CreateUserFormData]): Future[Result] = {
      Future.successful(BadRequest(views.html.createUser(form, request.user.firstName, createUserPageTitle, error = Some(invalidFormMessage))))
    }

    def handleValidForm(createUserFormData: CreateUserFormData): Future[Result] = {
      createNewUser(createUserFormData) map { consumerId =>
        Redirect(routes.Application.editUserPage(consumerId))
      } recover {
        case ConflictFailure(errorMessage) => Conflict(views.html.createUser(createUserForm, request.user.firstName, createUserPageTitle, error = Some(errorMessage)))
        case GenericFailure(errorMessage) => InternalServerError(views.html.createUser(createUserForm, request.user.firstName, createUserPageTitle, error = Some(errorMessage)))
      }
    }
    createUserForm.bindFromRequest.fold[Future[Result]](handleInvalidForm, handleValidForm)
  }

  def editUserPage(id: String) = maybeAuth { implicit request =>
    val consumer = dynamo.getUserWithId(id)
    val userKeys = dynamo.getAllKeysWithId(id)
    val filledForm = editUserForm.fill(EditUserFormData(consumer.email, consumer.name, consumer.company, consumer.url))

    Ok(views.html.editUser(id, filledForm, request.user.firstName, userKeys, editUserPageTitle))
  }

  def editUser(id: String) = maybeAuth.async { implicit request =>
    val userKeys = dynamo.getAllKeysWithId(id)

    def handleInvalidForm(form: Form[EditUserFormData]): Future[Result] = {
      Future.successful(BadRequest(views.html.editUser(id, form, request.user.firstName, userKeys, editUserPageTitle, error = Some(invalidFormMessage))))
    }

    def handleValidForm(form: EditUserFormData): Future[Result] = {
      val updatedUser = BonoboUser(id, form)
      dynamo.updateBonoboUser(updatedUser)

      Future.successful(Ok(views.html.editUser(id, editUserForm.fill(form), request.user.firstName, userKeys, editUserPageTitle, success = Some("The user has been successfully updated."))))
    }

    editUserForm.bindFromRequest.fold(handleInvalidForm, handleValidForm)
  }

  def createKeyPage(userId: String) = maybeAuth { implicit request =>
    Ok(views.html.createKey(userId, createKeyForm, request.user.firstName, createKeyPageTitle))
  }

  def createKey(userId: String) = maybeAuth.async { implicit request =>
    def handleInvalidForm(brokenKeyForm: Form[CreateKeyFormData]): Future[Result] = {
      Future.successful(BadRequest(views.html.createKey(userId, brokenKeyForm, request.user.firstName, createKeyPageTitle, error = Some(invalidFormMessage))))
    }

    def handleValidForm(form: CreateKeyFormData): Future[Result] = {
      createNewKey(userId, form) map { _ =>
        Redirect(routes.Application.editUserPage(userId))
      } recover {
        case ConflictFailure(message) => Conflict(views.html.createKey(userId, createKeyForm, request.user.firstName, createKeyPageTitle, error = Some(s"Conflict failure: $message")))
        case GenericFailure(message) => InternalServerError(views.html.createKey(userId, createKeyForm, request.user.firstName, createKeyPageTitle, error = Some(s"Generic failure: $message")))
      }
    }

    createKeyForm.bindFromRequest.fold[Future[Result]](handleInvalidForm, handleValidForm)
  }

  def editKeyPage(keyValue: String) = maybeAuth { implicit request =>
    dynamo.retrieveKey(keyValue) match {
      case Some(value) => {
        val filledForm = editKeyForm.fill(EditKeyFormData(value.key, value.requestsPerDay,
          value.requestsPerMinute, value.tier, defaultRequests = false, value.status))
        Ok(views.html.editKey(value.bonoboId, filledForm, request.user.firstName, editKeyPageTitle))
      }
      case None => NotFound
    }
  }

  def editKey(keyValue: String) = maybeAuth.async { implicit request =>

    def handleValidForm(newFormData: EditKeyFormData): Future[Result] = {
      val oldKey = dynamo.retrieveKey(keyValue)
      oldKey match {
        case Some(value) => _editKey(value, newFormData)
        case None => Future.successful(NotFound)
      }
    }

    def handleInvalidForm(form: Form[EditKeyFormData]): Future[Result] = {
      val oldKey = dynamo.retrieveKey(keyValue)
      oldKey match {
        case Some(value) => {
          val error = if (form.errors(0).message.contains("requests")) Some(form.errors(0).message) else Some(invalidFormMessage)
          Future.successful(BadRequest(views.html.editKey(value.bonoboId, form, request.user.firstName, editKeyPageTitle, error = error)))
        }
        case None => Future.successful(NotFound)
      }
    }

    editKeyForm.bindFromRequest.fold[Future[Result]](handleInvalidForm, handleValidForm)
  }

  private def _editKey(oldKey: KongKey, newFormData: EditKeyFormData): Future[Result] = {
    val bonoboId = oldKey.bonoboId
    val kongId = oldKey.kongId
    def updateKongKeyOnDB(newFormData: EditKeyFormData): Unit = {
      val updatedKey = {
        if (newFormData.defaultRequests) {
          val defaultRateLimits = newFormData.tier.rateLimit
          KongKey(bonoboId, kongId, newFormData, oldKey.createdAt, defaultRateLimits)
        } else KongKey(bonoboId, kongId, newFormData, oldKey.createdAt, RateLimits(newFormData.requestsPerMinute, newFormData.requestsPerDay))
      }
      dynamo.updateKongKey(updatedKey)
    }

    def updateRateLimitsIfNecessary(): Future[Happy.type] = {
      if (oldKey.requestsPerDay != newFormData.requestsPerDay || oldKey.requestsPerMinute != newFormData.requestsPerMinute) {
        kong.updateConsumer(kongId, new RateLimits(newFormData.requestsPerMinute, newFormData.requestsPerDay))
      } else {
        Future.successful(Happy)
      }
    }

    def deactivateKeyIfNecessary(): Future[Happy.type] = {
      if (oldKey.status == "Active" && newFormData.status == "Inactive") {
        kong.deleteKey(kongId)
      } else {
        Future.successful(Happy)
      }
    }

    def activateKeyIfNecessary(): Future[String] = {
      if (oldKey.status == "Inactive" && newFormData.status == "Active") {
        kong.createKey(kongId, Some(oldKey.key))
      } else {
        Future.successful(oldKey.key)
      }
    }

    for {
      _ <- updateRateLimitsIfNecessary()
      _ <- deactivateKeyIfNecessary()
      _ <- activateKeyIfNecessary()
    } yield {
      updateKongKeyOnDB(newFormData)
      Redirect(routes.Application.editUserPage(bonoboId))
    }
  }

  def healthcheck = Action { Ok("OK") }
}

object Application {

  val keyRegexPattern = """^[-a-zA-Z0-9]*$""".r.pattern
  val invalidFormMessage = "Please correct the highlighted fields."
  val invalidKeyMessage = "Invalid key: use only a-z, A-Z, 0-9 and dashes."
  val invalidTierMessage = "Invalid tier"
  val createUserPageTitle = "Create user"
  val editUserPageTitle = "Edit user"
  val createKeyPageTitle = "Create key"
  val editKeyPageTitle = "Edit key"

  case class CreateUserFormData(email: String, name: String, company: String, url: String, tier: Tier, key: Option[String] = None)

  val createUserForm: Form[CreateUserFormData] = Form(
    mapping(
      "email" -> email,
      "name" -> nonEmptyText,
      "company" -> nonEmptyText,
      "url" -> nonEmptyText,
      "tier" -> nonEmptyText.verifying(invalidTierMessage, tier => Tier.isValid(tier)).transform(tier => Tier.withName(tier).get, (tier: Tier) => tier.toString),
      "key" -> optional(text.verifying(invalidKeyMessage, key => keyRegexPattern.matcher(key).matches()))
    )(CreateUserFormData.apply)(CreateUserFormData.unapply)
  )

  case class EditUserFormData(email: String, name: String, company: String, url: String)

  val editUserForm: Form[EditUserFormData] = Form(
    mapping(
      "email" -> email,
      "name" -> nonEmptyText,
      "company" -> nonEmptyText,
      "url" -> nonEmptyText
    )(EditUserFormData.apply)(EditUserFormData.unapply)
  )

  case class CreateKeyFormData(key: Option[String], tier: Tier)

  val createKeyForm: Form[CreateKeyFormData] = Form(
    mapping(
      "key" -> optional(text.verifying(invalidKeyMessage, key => keyRegexPattern.matcher(key).matches())),
      "tier" -> nonEmptyText.verifying(invalidTierMessage, tier => Tier.isValid(tier)).transform(tier => Tier.withName(tier).get, (tier: Tier) => tier.toString)
    )(CreateKeyFormData.apply)(CreateKeyFormData.unapply)
  )

  case class EditKeyFormData(key: String, requestsPerDay: Int, requestsPerMinute: Int, tier: Tier, defaultRequests: Boolean, status: String) {
    def validateRequests: Boolean = requestsPerDay >= requestsPerMinute
  }

  val editKeyForm: Form[EditKeyFormData] = Form(
    mapping(
      "key" -> nonEmptyText,
      "requestsPerDay" -> number,
      "requestsPerMinute" -> number,
      "tier" -> nonEmptyText.verifying(invalidTierMessage, tier => Tier.isValid(tier)).transform(tier => Tier.withName(tier).get, (tier: Tier) => tier.toString),
      "defaultRequests" -> boolean,
      "status" -> nonEmptyText
    )(EditKeyFormData.apply)(EditKeyFormData.unapply) verifying ("The number of requests per day is smaller than the number of requests per minute.", data => data.validateRequests)
  )

  case class SearchFormData(query: String)

  val searchForm = Form(
    mapping(
      "query" -> nonEmptyText(minLength = 2, maxLength = 45)
    )(SearchFormData.apply)(SearchFormData.unapply)
  )
}
