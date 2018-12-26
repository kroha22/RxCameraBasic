package com.example.rxCameraBasic.rxcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.reactivex.Observable
import java.io.File
import java.io.FileOutputStream


/*
Сохраняет JPEG [Изображение] в указанный [File].
 */
internal object ImageSaverRxWrapper {
    /*
    Запись в файл
     */
    fun save(bitmap: Bitmap, file: File): Observable<File> {
        return Observable.create {emitter->
            file.createNewFile()
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            fos.close()
            emitter.onNext(file)
        }
    }

    fun getBitmap(cameraData: CameraWrap.CameraData): Observable<Bitmap> {
        return Observable.create {
            val bitmap = BitmapFactory.decodeByteArray(cameraData.cameraData, 0, cameraData.cameraData!!.size)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    cameraData.rotateMatrix, false)
        }
    }

}
