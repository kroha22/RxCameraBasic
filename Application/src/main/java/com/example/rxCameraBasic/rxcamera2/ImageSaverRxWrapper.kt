package com.example.rxCameraBasic.rxcamera2

import android.annotation.TargetApi
import android.media.Image
import android.media.ImageReader
import io.reactivex.Observable
import io.reactivex.Single
import java.io.File
import java.io.FileOutputStream


/*
Сохраняет JPEG [Изображение] в указанный [File].
 */
@TargetApi(21)
internal object ImageSaverRxWrapper {
    /*
    Запись в файл
     */
    fun save(image: Image, file: File): Single<File> {
        return Single.fromCallable {
            image.use { image ->
                FileOutputStream(file).channel.use { output ->
                    output.write(image.planes[0].buffer)
                    file
                }
            }
        }
    }
    /*
     Возвращает Observable, который будет генерировать сообщение каждый раз, когда ImageReader будет готов предоставить изображение
     */
    fun createOnImageAvailableObservable(imageReader: ImageReader): Observable<ImageReader> {
        return Observable.create { subscriber ->

            val listener = ImageReader.OnImageAvailableListener{ reader ->
                if (!subscriber.isDisposed) {
                    subscriber.onNext(reader)
                }
            }
            imageReader.setOnImageAvailableListener(listener, null)
            subscriber.setCancellable { imageReader.setOnImageAvailableListener(null, null) } //remove listener on unsubscribe
        }
    }
}
