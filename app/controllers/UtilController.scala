package controllers

import securesocial.core.{SecureSocial, RuntimeEnvironment}
import auth.{SecureSocialAuth, SocialUser}
import play.api.libs.json.Json
import play.api.Logger
import dao.TagDao
import scala.concurrent.Await
import scala.concurrent.duration._

class UtilController (override implicit val env: RuntimeEnvironment[SocialUser]) extends SecureSocial[SocialUser]  with SecureSocialAuth{

  def about = UserAwareAction {
    implicit request =>
      Ok(views.html.about(request.user))
  }
}