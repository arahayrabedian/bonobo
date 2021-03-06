package controllers

import models.Tier

object Forms {

  case class CreateUserFormData(name: String, email: String, companyName: Option[String], companyUrl: Option[String], productName: String, productUrl: Option[String], tier: Tier, key: Option[String] = None, sendEmail: Boolean, labelIds: String)

  case class EditUserFormData(name: String, email: String, companyName: Option[String], companyUrl: Option[String], labelIds: String)

  case class CreateKeyFormData(key: Option[String], tier: Tier, productName: String, productUrl: Option[String], sendEmail: Boolean)

  case class EditKeyFormData(key: String, productName: String, productUrl: Option[String], requestsPerDay: Int, requestsPerMinute: Int, tier: Tier, defaultRequests: Boolean, status: String) {
    def validateRequests: Boolean = requestsPerDay >= requestsPerMinute
  }

  case class SearchFormData(query: String)

  case class DeveloperCreateKeyFormData(name: String, email: String, productName: String, productUrl: Option[String], companyName: Option[String], companyUrl: Option[String], acceptTerms: Boolean)

  case class CommercialRequestKeyFormData(name: String, email: String, productName: String, productUrl: String, companyName: String, companyUrl: String,
    businessArea: String, monthlyUsers: Int, commercialModel: String, content: String, articlesPerDay: Int, contentFormat: String, acceptTerms: Boolean)

}
