package rpgboss.player
import rpgboss.player.entity._
import rpgboss.model._
import rpgboss.model.resource._
import com.badlogic.gdx.graphics._
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.Gdx
import java.util.concurrent.FutureTask
import java.util.concurrent.Callable
import akka.dispatch.Await
import akka.util.Duration

/**
 * This class contains all the state information about the game.
 * 
 * It must ensure that different threads can mutate the state of the game
 * without causing concurrency errors.
 * 
 * Moreover, OpenGL operations may only occur on the GDX rendering thread.
 * This object must post those operations to that thread via postRunnable
 */
class GameState(game: MyGame, project: Project) {
  // No need for syncronization, since it's a synchronized collection
  val windows = new collection.mutable.ArrayBuffer[Window] 
      with collection.mutable.SynchronizedBuffer[Window]
  
  // Should only be accessed on the Gdx thread, so no synchronization needed
  val pictures = new Array[PictureInfo](32)
  
  // Changes to this need to synchronize on this object
  var curTransition: Option[Transition] = None
  
  // Called every frame... by MyGame's render call. 
  def update() = {
    curTransition.synchronized {
      curTransition map { transition =>
        if(transition.done) {
          curTransition = None
        }
      }
    }
  }
  
  /**
   * Run the following on the GUI thread synchronously...
   */
  private def syncRun(op: => Any) = {
    val runnable = new Runnable() {
      def run() = op
    }
    Gdx.app.postRunnable(runnable)
  }
  
  /**
   * Calls the following on the GUI thread. Takes a frame's worth of time.
   */
  private def syncCall[T](op: => T): T = {
    val callable = new Callable[T]() {
      def call() = op
    }
    val future = new FutureTask(callable)
    
    Gdx.app.postRunnable(future)
    
    future.get
  }
  
  def setTransition(startAlpha: Float, endAlpha: Float, durationMs: Int) = {
    curTransition.synchronized {
      curTransition = Some(Transition(startAlpha, endAlpha, durationMs))
    }
  }
  
  def showPicture(slot: Int, name: String, x: Int, y: Int, w: Int, h: Int) =  
    syncRun {
      pictures(slot) = PictureInfo(project, name, x, y, w, h)
    }
  
  def hidePicture(slot: Int) = syncRun {
    pictures(slot).dispose()
    pictures(slot) = null
  }
  
  def choiceWindow(
      choices: Array[String],
      x: Int, y: Int, w: Int, h: Int,
      justification: Int) : Int= {
    val window = new ChoiceWindow(
        game.assets, 
        project,
        choices,
        x, y, w, h,
        game.screenLayer.windowskin, 
        game.screenLayer.windowskinRegion, 
        game.screenLayer.fontbmp,
        initialState = Window.Opening,
        msPerChar = 0,
        justification = justification)
    
    windows.prepend(window)
    game.inputs.prepend(window)
    
    // Return the choice... eventually...
    val choice = Await.result(window.result.future, Duration.Inf)
    
    game.inputs.remove(window)
    windows -= window
    
    choice
  }
  
  val LEFT = Window.Left
  val CENTER = Window.Center
  val RIGHT = Window.Right
}

/**
 * Need call on dispose first
 */
case class PictureInfo(
    project: Project, 
    name: String, 
    x: Int, y: Int, w: Int, h: Int) {
  val picture = Picture.readFromDisk(project, name)
  val texture = 
    new Texture(Gdx.files.absolute(picture.dataFile.getAbsolutePath()))
  
  def dispose() = texture.dispose()
  
  def render(batch: SpriteBatch) = {
    batch.draw(texture,
        x, y, w, h,
        0, 0, texture.getWidth(), texture.getHeight(),
        false, true)
  }
}