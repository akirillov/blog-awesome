package service

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import dao.{TagDao, PostDao}
import domain.{ViewPage, PostPreview, Post}
import play.api.libs.json.JsValue
import util.{StringAndDateUtils, JsonPostTransformer}
import java.util.Date
import play.api.Logger
import metrics.ApplicationMetrics._
import scala.concurrent.ExecutionContext.Implicits.global
import domain.DomainJsonFormats._


class PostService(postDao: PostDao, tagDao: TagDao) {

  private val logger = Logger("[PostService]")

  def getPostById(uid: String): Option[Post] = {
    logger.info(s"Attempting to get post by id [$uid]")
    val context = couchbaseReadTime.time()

    val post = Await.result(postDao.get(uid), 5 seconds)

    logger.info(s"Post lookup by id [$uid] found: ${post.isDefined}")
    couchbaseReadRequests.mark()
    context.stop()

    post
  }

  def getPosts(pageNum: Option[Int]): List[PostPreview] = {
    logger.info(s"Attempting to get published posts page #${pageNum.getOrElse(1)}")
    val context = couchbaseMRTime.time()

    val previews = getPostPreviews(postDao.findSubmittedPosts(pageNum.getOrElse(1)))

    logger.info(s"${previews.size} published posts found for page #${pageNum.getOrElse(1)}")
    couchbaseMRRequests.mark()
    context.stop()

    previews
  }

  def getDrafts(pageNum: Option[Int]): List[PostPreview] = {
    logger.info(s"Attempting to get drafts page #${pageNum.getOrElse(1)}")
    val context = couchbaseMRTime.time()

    val previews = getPostPreviews(postDao.findDrafts(pageNum.getOrElse(1)))

    logger.info(s"${previews.size} drafts found for page #${pageNum.getOrElse(1)}")
    couchbaseMRRequests.mark()
    context.stop()

    previews
  }

  def getPostsByTag(tag: String, pageNum: Option[Int]): List[PostPreview] = {
    logger.info(s"Attempting to get posts by tag [$tag], page #${pageNum.getOrElse(1)}")
    val context = couchbaseMRTime.time()

    val previews = getPostPreviews(postDao.findPostsByTag(tag, pageNum.getOrElse(1)))

    logger.info(s"${previews.size} posts with tag [$tag] found for page #${pageNum.getOrElse(1)}")
    couchbaseMRRequests.mark()
    context.stop()

    previews
  }

  def deletePostById(uid: String) = {
    logger.info(s"Deleting post by uid [$uid]")
    postDao.delete(uid)
  }

  private def getPostPreviews(awaitablePosts: Future[List[Post]]): List[PostPreview] = {
    Await.result(awaitablePosts, 5 seconds) map { post => PostPreview.fromPost(post)}
  }

  def saveJsonPost(json: JsValue): Either[String,String] = {
    logger.info("Saving post from JSON")

    val newPost = JsonPostTransformer.createPostFromJson(json)

    newPost map { p =>
      logger.info("JSON parsed successfully")
      val context = couchbaseWriteTime.time()

      val generatedUID = StringAndDateUtils.generateUID(p.title)
      val dateAsMillis = new Date().getTime

      postDao.save(generatedUID, newPost.get.copy(id = Some(generatedUID), isDraft = true, displayedDate = StringAndDateUtils.getCurrentDateAsString, date = dateAsMillis))

      logger.info(s"Post saved with uid [$generatedUID]")
      couchbaseWriteRequests.mark()
      context.stop()

      Right(generatedUID)
    } getOrElse Left(s"Json hasn't been parsed correctly:\n${json.toString}")
  }

  def updateExistingPost(uid: String, json: JsValue): Either[String,String] = {
    logger.info(s"Updating post with uid [$uid] from JSON")

    val newPost = JsonPostTransformer.createPostFromJson(json)

    newPost map { p =>
      logger.info("JSON parsed successfully")
      val context = couchbaseWriteTime.time()

      val generatedUID = StringAndDateUtils.generateUID(p.title)

      getPostById(uid.trim) map { savedPost =>
        logger.info("Post for update found")

        postDao.save(generatedUID, newPost.get.copy(id = Some(generatedUID), displayedDate = savedPost.displayedDate, date = savedPost.date))

        if (uid != generatedUID) {
          postDao.delete(uid)
          logger.info(s"Post saved with uid [$generatedUID], old post deleted")
        } else {
          logger.info(s"Post with uid [$generatedUID] updated")
        }

        couchbaseWriteRequests.mark()
        context.stop()

        Right(generatedUID)
      } getOrElse Left(s"Post with uid:${uid.trim} not found!")
    } getOrElse Left(s"Json hasn't been parsed correctly:\n${json.toString}")
  }

  def publishPost(uid: String): Either[String, Boolean] = {
    getPostById(uid) map { post =>
      logger.info(s"Publishing post with uid [$uid]")
      val context = couchbaseWriteTime.time()

      val newPost = post.copy(isDraft = false)

      postDao.save(uid, newPost)

      tagDao.mergeTags(post.tags) //! happens only when post is being published to minimize amount of trash tags

      logger.info(s"Post with uid [$uid] published")
      couchbaseWriteRequests.mark()
      context.stop()

      Right(true)
    } getOrElse Left(s"Post with uid: $uid not found!")
  }

  def createViewPage(sourceURL: String, previews: List[PostPreview], pageNum: Option[Int]): ViewPage = {

    pageNum match {
      case Some(page) => {
        val prev = if(page == 2) "" else s"?page=${page-1}"

        if(previews.size == ViewPage.PageSize){
          ViewPage(previous = Some(sourceURL + prev), next = Some(sourceURL+s"?page=${page+1}"))
        } else {
          ViewPage(previous = Some(sourceURL + prev), next = None)
        }

      }
      case None => {
        if(previews.size == ViewPage.PageSize){
          ViewPage(previous = None, next = Some(sourceURL+s"?page=2"))
        } else {
          ViewPage(previous = None, next = None)
        }
      }
    }
  }
}
