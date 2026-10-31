package com.dailyflow.app.widget

import kotlin.math.*

data class WidgetLayoutPolicy(val visibleRows:Int,val rowHeight:Float,val footerHeight:Float,val showBrand:Boolean,val dateFontSize:Float)
fun widgetLayoutPolicy(width:Float,height:Float,fontScale:Float):WidgetLayoutPolicy{
    val scale=fontScale.coerceIn(.85f,3f);val brand=scale<1.6f;val dateSize=if(width<280f)16f else 18f
    val header=max(48f,((if(brand)12f else 0f)+dateSize*(if(!brand)2f else 1f))*scale*1.3f)
    val row=max(48f,14f*scale*2f*1.2f+12f)
    val footer=max(36f,13f*scale*1.3f+8f)
    val remaining=height-24f-header-13f*scale*1.3f-4f-footer
    val slots=if(height>=360f)5 else 3
    return WidgetLayoutPolicy(floor(remaining/row).toInt().coerceIn(0,slots),row,footer,brand,dateSize)
}
