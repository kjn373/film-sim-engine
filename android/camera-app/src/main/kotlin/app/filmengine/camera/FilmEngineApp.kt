package app.filmengine.camera

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.filmengine.camera.core.CameraController
import app.filmengine.camera.core.PreviewPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class FilmEngineApp : Application()

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun cameraController(@ApplicationContext context: Context): CameraController =
        CameraController(context)

    @Provides
    @Singleton
    fun previewPipeline(): PreviewPipeline = PreviewPipeline()

    @Provides
    @Singleton
    fun editDatabase(@ApplicationContext context: Context): EditDatabase =
        Room.databaseBuilder(context, EditDatabase::class.java, "edits.db").build()

    @Provides
    fun editHistoryDao(db: EditDatabase): EditHistoryDao = db.editHistoryDao()
}
