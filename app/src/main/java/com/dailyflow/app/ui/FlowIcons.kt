package com.dailyflow.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small original line drawings; no third-party or reference-app assets. */
object FlowIcons {
    private fun line(name:String,draw:PathBuilder.()->Unit)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply{
        path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
    }.build()
    val Add=line("add"){moveTo(12f,5f);lineTo(12f,19f);moveTo(5f,12f);lineTo(19f,12f)}
    val Calendar=line("calendar"){moveTo(6f,4f);lineTo(18f,4f);quadTo(20f,4f,20f,6f);lineTo(20f,19f);quadTo(20f,21f,18f,21f);lineTo(6f,21f);quadTo(4f,21f,4f,19f);lineTo(4f,6f);quadTo(4f,4f,6f,4f);close();moveTo(8f,2f);lineTo(8f,6f);moveTo(16f,2f);lineTo(16f,6f);moveTo(4f,9f);lineTo(20f,9f);moveTo(8f,13f);lineTo(10f,13f);moveTo(14f,13f);lineTo(16f,13f);moveTo(8f,17f);lineTo(10f,17f)}
    val History=line("history"){moveTo(4f,9f);curveTo(5f,4f,10f,2f,15f,4f);curveTo(22f,7f,21f,18f,14f,20f);curveTo(10f,22f,5f,19f,4f,16f);moveTo(3f,4f);lineTo(3f,10f);lineTo(8f,10f);moveTo(12f,7f);lineTo(12f,12f);lineTo(16f,14f)}
    val Repeat=line("repeat"){moveTo(4f,9f);lineTo(4f,7f);quadTo(4f,5f,7f,5f);lineTo(20f,5f);moveTo(17f,2f);lineTo(20f,5f);lineTo(17f,8f);moveTo(20f,15f);lineTo(20f,17f);quadTo(20f,19f,17f,19f);lineTo(4f,19f);moveTo(7f,16f);lineTo(4f,19f);lineTo(7f,22f)}
}
