package com.lifetrack
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import com.lifetrack.data.*
import com.lifetrack.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class) class LiveFoodWorkflowTest {
 @Test fun realMlkitImageToOffToPortionToDiarySurvivesReopen()=runBlocking {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext;val bitmap=instrumentation.context.assets.open("barcode-nutella.png").use{BitmapFactory.decodeStream(it)};val scanner=BarcodeScanning.getClient()
  val code=try{Tasks.await(scanner.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS).first().rawValue!!}finally{scanner.close();bitmap.recycle()}
  assertEquals("3017620422003",code)
  context.deleteDatabase("live-food-test")
  var db=Room.databaseBuilder(context,LifeDatabase::class.java,"live-food-test").build()
  try {val catalog=LocalCatalogProvider(db);val product=catalog.getByBarcode(code)!!;assertTrue(product.name.isNotBlank());assertTrue(product.calories!!>0);val log=Portions.log(product,15000,product.baseUnit,"SNACK",1000);val repo=LocalFoodRepository(db);repo.save(log);val saved=repo.snapshots.first().logs.single();assertEquals(log.calories,saved.calories);db.close();db=Room.databaseBuilder(context,LifeDatabase::class.java,"live-food-test").build();assertEquals(saved,LocalFoodRepository(db).snapshots.first().logs.single());assertEquals(product,db.productDao().catalog().first().single())}finally{db.close();context.deleteDatabase("live-food-test")}
 }
}
