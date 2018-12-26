package com.example.rxCameraBasic

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import android.view.View

/*
  Обертка [TextureView] с возможностью задать нужные пропорции сторон.
 */
class AutoFitTextureView @JvmOverloads constructor(context: Context,
                                                   attrs: AttributeSet? = null,
                                                   defStyle: Int = 0) : TextureView(context, attrs, defStyle) {
    private var widthRatio = 0
    private var heightRatio = 0
    /*
      Установка соотношения сторон для View.
     */
    fun setAspectRatio(widthRatio: Int, heightRatio: Int) {
        if (widthRatio < 0 || heightRatio < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }

        this.widthRatio = widthRatio
        this.heightRatio = heightRatio

        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)

        if (0 == widthRatio || 0 == heightRatio) {
            setMeasuredDimension(width, height)

        } else {
            if (width < height * widthRatio / heightRatio) {
                setMeasuredDimension(width, width * heightRatio / widthRatio)

            } else {
                setMeasuredDimension(height * widthRatio / heightRatio, height)
            }
        }
    }

}
