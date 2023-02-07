package com.example.hello

import android.Manifest
import android.app.Service
import android.content.ContentProviderClient
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.google.gson.GsonBuilder

import io.socket.client.IO
import io.socket.client.Socket

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.net.URISyntaxException

import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.IOException
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationListener: LocationListener

    // 시작여부 판별
    private var isStart = false

    // 가속도 데이터 센서를 관리하는 변수
    private var accSensor: Sensor? = null

    // 가속도 데이터 저장을 위한 리스트
    private var accList = mutableListOf<AccData>()

    // 오디오 파일
    private lateinit var recordFile: File
    private var mediaRecorder: MediaRecorder? = null

    // HTTP 프로토콜을 위한 옵션
    private val apis = Apis.create()

    // 웹소켓
    private var mSocket: Socket? = null

    // 기타 옵션
    private var startAt: Long = 0
    private var endAt: Long = 0
    private var createdDateTime: Long = 0
    private var collectId: Long = 0

    // Firebase 설정
    private val storage = Firebase.storage

    // 권한 설정과 관련된 옵션
    private var requiredPermission = arrayOf(
        android.Manifest.permission.BODY_SENSORS,
        android.Manifest.permission.RECORD_AUDIO,
//        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    // 시작점 역할을 하는 함수
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // 부모(Parent) 클래스를 상속받기 때문에 부모 클래스의 onCreate 호출
        setContentView(R.layout.activity_main) // 화면에 무엇을 보여줄 것인지 결정하는 함수

        var isGranted = false; // 처음 시작시 권한부여가 되지 않음
        for(permission in requiredPermission){ // 필요한 권한마다 권한을 요청함
            if(checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED){
//                isGranted = true;
                break;
            }
        }

        print("승인여부: $isGranted\n")
        if(!isGranted){ // 만약 권한이 승인되지 않은 경우 권한을 재요청
            requestPermissions(requiredPermission, 0);
        }
        init()

        // 스마트폰 UI 상에 표시된 부분 확인하기
        val btn_event = findViewById<Button>(R.id.e_btn)
        val x = findViewById<TextView>(R.id.accX)
        val y = findViewById<TextView>(R.id.accY)
        val z = findViewById<TextView>(R.id.accZ)
        btn_event.setOnClickListener {
            if(isStart){ // 수집 중단
                endAt = System.currentTimeMillis()

                mSocket!!.close() // 웹 소켓 종료

//                stopRecord() // 녹음 중단

                val body = UploadBody(accList, startAt, endAt, "SENSOR_DELAY_GAME", createdDateTime, "smartphone", collectId )
                apis.uploadData(body).enqueue(object: Callback<UploadResponse>{
                    override fun onResponse(
                        call: Call<UploadResponse>,
                        response: Response<UploadResponse>
                    ) {
                        Log.d("데이터 전송", "성공")
                    }

                    override fun onFailure(call: Call<UploadResponse>, t: Throwable) {
                        Log.d("데이터 전송", "실패")
                    }
                })
//                uploadMicFirebase(recordFile)
                uploadDataFirebase(body)

                Toast.makeText(this, "데이터 수집을 중단합니다", Toast.LENGTH_LONG).show();
                btn_event.text = "수집시작"
                x.text = "대기"
                y.text = "대기"
                z.text = "대기"
                isStart = false
            }else{ // 수집시작
                Toast.makeText(this, "데이터 수집을 시작합니다", Toast.LENGTH_LONG).show();
                btn_event.text = "수집중단"

                accList.clear(); // 가속도 리스트 초기화

                initSocket() // 웹 소켓 시작

//                startRecord() // 녹음시작

                collectId = System.currentTimeMillis()
                createdDateTime = System.currentTimeMillis()
                startAt = System.currentTimeMillis()
                isStart = true
            }
        }


    }

    private fun init(){
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.apply {
            accSensor = getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("Location", "권한 거부")
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location?->
                val lat = location?.latitude
                val lng = location?.longitude
                Toast.makeText(applicationContext, "위도 $lat 경도 $lng", Toast.LENGTH_LONG).show()
              }
            .addOnFailureListener({Toast.makeText(applicationContext, "위치 데이터 수집 실패", Toast.LENGTH_LONG).show()})

//        makeLocaionListener()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.apply {
            registerListener(this@MainActivity, accSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }


    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if(event != null){
            when(event.sensor.type){
                Sensor.TYPE_ACCELEROMETER -> getAccelerometerData(event)
            }
        }
    }

    // 가속도 데이터 센서값을 받아들이는 함수
    private fun getAccelerometerData(event: SensorEvent) {
        val tag:String = "Accelerometer"
        val axisX: Float = event.values[0]
        val axisY: Float = event.values[1]
        val axisZ: Float = event.values[2]
        if (isStart) {
            val x = findViewById<TextView>(R.id.accX)
            val y = findViewById<TextView>(R.id.accY)
            val z = findViewById<TextView>(R.id.accZ)

            x.text = axisX.toString()
            y.text = axisY.toString()
            z.text = axisZ.toString()

            val accData = AccData(accList.size, axisX, axisY, axisZ, System.currentTimeMillis())
            mSocket!!.emit("acc", accData.toString())
            accList.add(accData)
        }
    }

    private fun startRecord() {
        val tag = "startRecord"
        val time = System.currentTimeMillis()
        recordFile = File.createTempFile("$time", ".mp3", cacheDir)
        Log.d(tag, recordFile.absolutePath)
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(recordFile)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(tag, "prepare() fail")
            }
            start()
        }
    }

    private fun stopRecord() {
        val tag = "stopRecord"
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: IOException) {
                Log.e(tag, e.stackTraceToString())
            }
            mediaRecorder = null
        }
    }

    private fun uploadMicFirebase(data: File){
        val storageRef = storage.reference
        val pathString = "mic/" + collectId.toString() + ".mp3"
        val pathRef = storageRef.child(pathString)

//        var builder = StorageMetadata.Builder().setContentType("audio/mp3")
//        var metadata = builder.contentType

        Log.d("태그", pathRef.toString())

        var uploadTask = pathRef.putFile(Uri.fromFile(data))
        uploadTask.addOnFailureListener{
            Log.d("fb", "파이어베이스 전송 실패")
        }.addOnSuccessListener {
            Log.d("fb", "파이어베이스 전송 성공 ")
        }
    }


    private fun uploadDataFirebase(body: UploadBody){
        // Create a storage reference from our app
        val storageRef = storage.reference
        val pathString = "data/" + collectId.toString() + ".json"
        val pathRef = storageRef.child(pathString)

        val gson: Gson = GsonBuilder().setLenient().create()
        var data = gson.toJson(body).toByteArray()
        Log.d("데이터", data.toString())
        var uploadTask = pathRef.putBytes(data)
        uploadTask.addOnFailureListener{
            Log.d("fb", "파이어베이스 전송 실패")
        }.addOnSuccessListener {
            Log.d("fb", "파이어베이스 전송 성공 ")
        }
    }

    private fun initSocket() {
        try {
            mSocket = IO.socket("http://210.107.206.176:3000/smartphone")
            Log.d("SOCKET", "Connection success : ")
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        mSocket!!.connect()
    }

    private fun makeLocaionListener(){
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 위치 정보 전달 목적으로 호출(자동으로 호출)

                val longitude = location.longitude
                val latitude = location.latitude

                Log.d("Location", "Latitude : $latitude, Longitude : $longitude")
            }
        }
    }

//    private fun makeLocationTimer(){
//
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            // TODO: Consider calling
//            //    ActivityCompat#requestPermissions
//            // here to request the missing permissions, and then overriding
//            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
//            //                                          int[] grantResults)
//            // to handle the case where the user grants the permission. See the documentation
//            // for ActivityCompat#requestPermissions for more details.
//            return
//        }
//
//        Timer().schedule(object: TimerTask(){
//            override fun run() {
//                fusedLocationClient.getLastLocation()
//            }
//        }, 5000)
//    }





}
