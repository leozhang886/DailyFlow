package com.dailyflow.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object FlowDesign { val Page=20.dp;val Gap=12.dp;val Radius=20.dp;val Touch=48.dp;val MaxWidth=760.dp }
private val DayColors=lightColorScheme(primary=Color(0xFF2454C5),onPrimary=Color.White,primaryContainer=Color(0xFFE0E9FF),onPrimaryContainer=Color(0xFF15377B),
    secondary=Color(0xFF476454),secondaryContainer=Color(0xFFDAEEE0),onSecondaryContainer=Color(0xFF173D27),
    background=Color(0xFFF6F8FC),onBackground=Color(0xFF172033),surface=Color.White,onSurface=Color(0xFF172033),
    surfaceTint=Color(0xFF2454C5),surfaceDim=Color(0xFFE2E7EF),surfaceBright=Color.White,
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF1F4FA),surfaceContainer=Color(0xFFEDF1F8),surfaceContainerHigh=Color(0xFFE9EEF6),surfaceContainerHighest=Color(0xFFE6EBF3),
    surfaceVariant=Color(0xFFEBEFF6),onSurfaceVariant=Color(0xFF475569),outline=Color(0xFF718096),outlineVariant=Color(0xFFDDE3ED))
private val NightColors=darkColorScheme(primary=Color(0xFFAAC7FF),onPrimary=Color(0xFF15377B),primaryContainer=Color(0xFF263F71),onPrimaryContainer=Color(0xFFDCE7FF),
    secondary=Color(0xFFA7D5B5),secondaryContainer=Color(0xFF284D37),onSecondaryContainer=Color(0xFFDAEEE0),
    background=Color(0xFF0E1522),onBackground=Color(0xFFF1F5F9),surface=Color(0xFF182131),onSurface=Color(0xFFF1F5F9),
    surfaceTint=Color(0xFFAAC7FF),surfaceDim=Color(0xFF0E1522),surfaceBright=Color(0xFF303E52),
    surfaceContainerLowest=Color(0xFF0B111D),surfaceContainerLow=Color(0xFF141D2B),surfaceContainer=Color(0xFF182131),surfaceContainerHigh=Color(0xFF233045),surfaceContainerHighest=Color(0xFF2A374A),
    surfaceVariant=Color(0xFF263143),onSurfaceVariant=Color(0xFFCBD5E1),outline=Color(0xFFA0AEC0),outlineVariant=Color(0xFF3A465B))
private val FlowTypography=Typography(
    headlineMedium=TextStyle(fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.SemiBold),
    headlineSmall=TextStyle(fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.SemiBold),
    titleLarge=TextStyle(fontSize=21.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold),
    titleMedium=TextStyle(fontSize=17.sp,lineHeight=25.sp,fontWeight=FontWeight.Medium),
    titleSmall=TextStyle(fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
    bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),
    labelLarge=TextStyle(fontSize=14.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium))
@Composable fun FlowTheme(dark:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit){
    MaterialTheme(colorScheme=if(dark)NightColors else DayColors,typography=FlowTypography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
@Composable fun PageFrame(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit){
    Box(modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){Box(Modifier.widthIn(max=FlowDesign.MaxWidth).fillMaxWidth().fillMaxHeight(),content=content)}
}
@Composable fun NoticeCard(text:String,modifier:Modifier=Modifier,error:Boolean=false,action:(@Composable ()->Unit)?=null){
    Surface(modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(text,color=if(error)MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}
@Composable fun SectionTitle(title:String,subtitle:String?=null){Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(title,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
    subtitle?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}}
@Composable fun StatusPill(text:String,emphasis:Boolean=false,error:Boolean=false){
    Surface(shape=RoundedCornerShape(8.dp),color=when{error->MaterialTheme.colorScheme.errorContainer;emphasis->MaterialTheme.colorScheme.primaryContainer;else->MaterialTheme.colorScheme.surfaceVariant}){
        Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,
            color=when{error->MaterialTheme.colorScheme.onErrorContainer;emphasis->MaterialTheme.colorScheme.onPrimaryContainer;else->MaterialTheme.colorScheme.onSurfaceVariant})
    }
}
