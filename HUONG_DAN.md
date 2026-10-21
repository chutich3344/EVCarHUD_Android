# EVCarHUD – thay đổi sau buổi test 19/10

File đã sửa: `MainActivity.kt`, `Hud.kt`, `CameraSource.kt`.
File mới: `UvcSource.kt` (camera USB/OTG), `NetStreamSource.kt` (camera hành trình Wi‑Fi), `AlertLog.kt` (nhật ký cảnh báo).
Các file khác giữ nguyên, không cần thay.

## 1. Capo gây kêu liên tục

**Nguyên nhân:** vùng YOLO nhìn được neo sát đáy khung hình. Đặt máy trên taplo thì capo nằm ở đáy khung,
YOLO nhận capo là "ô tô" có đáy hộp sát mép dưới, nên khoảng cách ước lượng chỉ 2–3 m và không đổi.
Khi xe chạy trên ~20 km/h, luật "bám đuôi rất sát" (khoảng cách < 0,6 × tốc độ × 1 s) bị kích hoạt liên tục.

**Sửa:**
- Thanh trượt mới **"Che vùng capo: X% đáy khung"** trong ⚙ (0–45%, **mặc định 15%**, lưu lại sau khi tắt app).
  Phần đáy bị che được tô tối và có vạch trắng mảnh để căn cho khớp mép capo.
- YOLO giờ nhìn dải đường phía trên vạch đó, không nhìn capo nữa.
- Hộp nào nằm trong vùng capo, hoặc chạm mép dưới mà rộng quá 60% khung (đặc trưng của capo/taplo), bị bỏ khỏi cảnh báo và hiển thị.
- Làn đường và quỹ đạo không còn vẽ đè lên capo.

Cách chỉnh: đặt máy như lúc chạy, mở ⚙, kéo thanh trượt đến khi vạch trắng nằm ngay mép capo.

## 2. Chọn video test bị khựng

**Nguyên nhân:** khi chọn video lúc camera đang chạy, lần chạy camera cũ chưa kịp thoát thì lần chạy video đã đặt
lại cờ `running = true`, nên các luồng camera cũ "sống lại" và chạy song song với video: hai nguồn cùng đẩy khung
lên màn hình và cùng dùng NPU. Ngoài ra camera + NPU vẫn chạy nặng trong lúc mở trình chọn file.

**Sửa:**
- Bấm **"Chọn video test"**: camera tạm dừng, mở trình chọn file.
- Chọn xong hiện hộp thoại **"Chạy video test? <tên file>"** với **Chạy / Huỷ**. Huỷ (hoặc thoát trình chọn) thì camera chạy lại như cũ.
- Mỗi lần chạy có số thứ tự riêng; lần chạy mới chờ lần cũ thả camera và NPU hẳn rồi mới bắt đầu.
- Bỏ nút "Chạy" video riêng; nút **"Chạy camera / Dừng"** dùng chung.

## 3. "Lưu outputs" là gì

Nút này ghi toàn bộ mảng số thô của lần suy luận gần nhất (khoảng 2.000+ số: vạch làn, quỹ đạo, xe dẫn đầu, tư thế…)
ra file `outputs_<số>.txt` trong `Android/data/com.kevin.evcarhud/files/`. Nó chỉ dùng để dò chỉ số đầu ra model lúc phát triển,
không có ích khi lái xe. **Vẫn giữ nguyên, chưa xoá.**

**Đã thêm "Nhật ký cảnh báo"** (nút trong ⚙, bật mặc định, file mới `AlertLog.kt`): mỗi khi một đợt cảnh báo bắt đầu
(kêu tít = mức 2, viền vàng = mức 1) app ghi một dòng vào `Android/data/com.kevin.evcarhud/files/alerts/alerts_<ngày>.csv`
(mở bằng Excel được): giờ, nguồn, mức, lý do (TTC / quá gần / bám đuôi), loại vật, khoảng cách, tốc độ áp sát, TTC,
tốc độ xe, toạ độ hộp và % che capo. Hộp nằm sát đáy khung (y2 lớn) và rộng gần hết khung thường là capo bị nhận nhầm.
Nếu thấy hữu ích thì có thể bỏ nút "Lưu outputs".

## 4. Camera USB qua OTG

Trong ⚙ → **Nguồn camera** → **USB (OTG)** → **Chạy camera**.

- Nếu máy hỗ trợ camera USB ngay trong Camera2 (một số máy Android 14+), app dùng luôn, không cần thư viện.
- Nếu không, app dùng thư viện **libuvc của AndroidUSBCamera (AUSBC)**. Thêm vào Gradle:

`settings.gradle.kts`
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`app/build.gradle.kts`
```kotlin
dependencies {
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.3.3")
}
```
Nếu sync báo không tìm thấy `libuvc`, đổi thành `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3` (gói này kéo theo libuvc).
Nếu chưa muốn thêm thư viện thì xoá `UvcSource.kt` và dòng `else UvcSource(ctx) { run.alive }` trong `MainActivity.kt` (thay bằng `else CameraSource(ctx, external = true) { run.alive }`).

Khi cắm camera lần đầu Android sẽ hỏi quyền truy cập USB, bấm Cho phép. Camera USB không báo được góc nhìn, nhớ chỉnh **FOV ngang** cho đúng camera.

## 5. Camera hành trình qua Wi‑Fi

Trong ⚙ → **Nguồn camera** → **Hành trình Wi‑Fi**, nhập địa chỉ luồng rồi **Chạy camera**. Không cần thư viện ngoài.

- `rtsp://…` : RTSP qua TCP, H.264 hoặc H.265, giải mã phần cứng. Có mật khẩu thì ghi `rtsp://user:pass@192.168.1.254/…`.
- `http://…` : luồng MJPEG (nhiều camera chip Novatek, thường `http://192.168.1.254:8192`).
- Mặc định điền `rtsp://192.168.0.1:554/livestream/12`: luồng xem trước của camera 70mai (Xiaomi) M300, nhiều khả năng dùng chung cho M310 / M310 Plus. Không cần mật khẩu, nhưng độ phân giải thấp (khoảng 360p).
- Camera chip Novatek (nhiều hãng khác) thường dùng `rtsp://192.168.1.254/xxx.mov`.
- Kết nối luôn đi qua Wi‑Fi của camera, kể cả khi Android báo "Wi‑Fi không có Internet" (khi đó chọn vẫn giữ kết nối).

**Cần biết hãng và mẫu camera hành trình** để điền đúng địa chỉ luồng. Một số hãng (ví dụ 70mai, Xiaomi) chỉ cho xem qua app riêng
và không mở RTSP; khi đó cần kiểm tra theo từng mẫu.

## Manifest cần thêm (`AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```
(`CAMERA` đã có sẵn; camera USB trên Android 9+ cũng cần quyền này.)

## Lưu ý

Các file chưa được build thử (không có Android SDK ở đây). Nếu Android Studio báo lỗi, gửi lại dòng lỗi để sửa.

## Đợt sửa theo chạy thực tế (19/10, tối)

File thay: `Hud.kt`, `MainActivity.kt`, `Weather.kt`.

- Hộp nhận diện bỏ nhãn chữ. Màu theo trạng thái: xanh lá an toàn, vàng chú ý, đỏ dễ va chạm. Chỉ tô màu cho đối tượng trong dải đường phía trước; đối tượng xung quanh viền xanh dương mờ.
- Nhãn loại xe ở giữa sáng và đậm hơn; số khoảng cách ở giữa chữ đậm. Muốn đổi font đậm thì đặt `assets/fonts/hud_bold.ttf` (gợi ý Exo 2 Bold).
- Đồng hồ tốc độ kiểu vòng tròn tối: cung bắt đầu ở đáy, hồng chuyển xanh ngọc rồi xanh lá neon; vượt giới hạn thì đỏ và chớp.
- Viền cảnh báo mỏng, vẽ ngay trong HUD, bao cả khung hình từ mép trên xuống vạch capo (vùng capo không tính). Đã bỏ viền dày toàn màn hình.
- Tự hiệu chỉnh chân trời bật mặc định, chạy từ ~20 km/h, và nhớ giá trị cho lần mở sau.
- Cài đặt > Cảnh báo > **Chế độ đường**: Trong phố / Cao tốc. Cao tốc từ 60 km/h áp khoảng cách tối thiểu 35 / 55 / 70 / 100 m. Gần hơn mức này là viền vàng; gần hơn một nửa là viền đỏ kèm tiếng tít. Trời mưa hoặc sương mù (theo thời tiết) tăng thêm 30%.
- Bỏ tên đường ở dòng 3 bên trái; thay bằng biểu tượng làn + chế độ đường đang chọn (TRONG PHỐ / CAO TỐC / TRỜI MƯA).
- Chế độ đường thêm **Mưa**: thời gian tới va chạm 2,5 s (đỏ) / 4,5 s (vàng) thay cho 1,8 / 3,5 s; giãn cách tối thiểu 1,5 s thay cho 1 s; từ 60 km/h khoảng cách luật +30% (46 / 72 / 91 / 130 m). Kẹt xe bò chậm vẫn không kêu.

## Đợt nâng cấp đêm 19/10

**File chép vào `app/src/main/java/com/kevin/evcarhud/`:** `Hud.kt`, `MainActivity.kt`, `Detector.kt`, `Tracker.kt`, `Alert.kt`, và 4 file mới `AlertVoice.kt`, `AlertClips.kt`, `VietMap.kt`, `VietMapNav.kt` (cộng `Weather.kt`, `CameraSource.kt` từ đợt tối nếu chưa chép).

### Cảnh báo mới
- **Xe cắt đầu**: xe ở ngoài dải đường của mình, trong 15 m, đang lấn ngang vào và sẽ chạm dải trong ~1,2 s. Dưới 7 m là đỏ + âm báo, xa hơn là vàng. Đang ôm cua hoặc đánh lái thì bỏ qua để khỏi báo nhầm.
- **Xe trước đã chạy**: đang đứng sau xe gần (< 15 m) hơn 2 s, xe đó đi thêm hơn 4 m thì nhắc một lần.
- **Vượt tốc độ**: hơn giới hạn 5 km/h liên tục 3 s thì nhắc, tối đa 30 s một lần.
- Khoảng cách tới xe máy / ô tô / xe tải giờ kết hợp điểm chạm đất (60%) và bề rộng thường gặp của loại xe đó (40%), ổn định hơn khi xe lệch làn.
- Đứng yên hơn 3 s thì tự giảm nhịp NPU và YOLO cho đỡ nóng máy; xe chạy lại là trở về ngay.

### Cài đặt mới
- **Cảnh báo > Âm báo: Tiếng tít / Giọng đọc.** Giọng đọc chỉ nói câu ngắn theo loại cảnh báo ("Phanh! Xe phía trước", "Quá gần, giảm tốc", "Xe cắt đầu bên trái", "Giữ khoảng cách", "Xe trước đã chạy", "Vượt tốc độ, giới hạn 60"), không đọc tên xe. Cần giọng tiếng Việt: Cài đặt Android > Hệ thống > Ngôn ngữ > Chuyển văn bản thành giọng nói > Google > cài dữ liệu giọng Tiếng Việt. Máy chưa có giọng Việt thì app tự dùng tiếng tít, dòng trạng thái bên dưới sẽ báo. Nút "Thử âm báo" để nghe thử.
- **Xem lại cảnh báo**: mỗi lần cảnh báo đỏ (và lần vàng do xe cắt đầu) app lưu 8 ảnh 2 s trước, 8 ảnh 2 s sau và 1 ảnh có HUD. Bấm Đúng / Nhầm cho từng lần; những lần "Nhầm" là dữ liệu quý để chỉnh ngưỡng và huấn luyện lại. Thư mục: `Android/data/com.kevin.evcarhud/files/alerts/clips/`.
- **Bản đồ VietMap > API key**: dán key VietMap vào ô này (key không nằm trong mã nguồn). Dùng để hiện tên đường ở dòng thứ 4 bên trái HUD. App chỉ tra khi xe đi thêm khoảng 150 m, nên một ngày chạy bình thường dùng rất ít so với hạn 1500 lần/ngày. VietMap không trả giới hạn tốc độ; giới hạn vẫn lấy theo thứ tự Waze, biển báo camera đọc được (khi có model riêng), rồi OSM.
- **Camera > Chiều cao camera** (0,6 đến 2,2 m) và **Hiệu chỉnh bằng khoảng cách thật**: dừng sau một xe, đo khoảng cách thật (bước chân hoặc thước), nhập số mét, app tự tính lại chiều cao camera.
- **Hiển thị > Phản chiếu kính lái**: nền đen, lật gương toàn màn hình, không vẽ hộp, để đặt điện thoại dưới kính lái cho hình phản chiếu lên kính.
- **Chẩn đoán > Thu ảnh huấn luyện**: 1 ảnh gốc mỗi 5 s khi xe chạy trên 10 km/h, tối đa 5000 ảnh, vào `files/dataset/`. Bật khi muốn gom ảnh để huấn luyện YOLO riêng, tắt khi không cần cho đỡ tốn bộ nhớ.

### Huấn luyện YOLO cho đường Việt Nam (một model gộp: vật cản + biển báo)
Chỉ dùng **một** model YOLO cho cả xe cộ và biển báo, không chạy thêm model thứ hai (đỡ nóng máy).

1. **Thu ảnh:** bật "Thu ảnh huấn luyện" chạy vài buổi (phố, cao tốc, đêm, mưa), hoặc lấy video từ dashcam 2K–4K. Từ video, trích 1–2 ảnh/giây và bỏ các đoạn đứng đèn đỏ:
   `ffmpeg -i video.mp4 -vf "fps=1,mpdecimate" -vsync vfr anh_%05d.jpg`
2. **Gán nhãn tự động phần vật cản:** chạy model YOLO hiện tại trên ảnh mới để tạo sẵn nhãn xe máy / ô tô / người… (Ultralytics: `yolo predict model=yolo11n.pt source=anh/ save_txt=True conf=0.4`). Nếu ảnh mới chỉ có nhãn biển báo, model sẽ quên cách nhận xe.
3. **Vẽ thêm nhãn biển báo** trên Roboflow hoặc CVAT: `speed_limit_40`, `speed_limit_50`, `speed_limit_60`, `speed_limit_80`… (và `stop_sign`, `traffic_light` nếu muốn). Kiểm tra lại nhãn xe tự động, sửa chỗ sai.
4. **Huấn luyện** YOLO11n (hoặc v8n) trên Colab:
   `yolo train model=yolo11n.pt data=data.yaml imgsz=960 epochs=150 fliplr=0.0 degrees=3 hsv_v=0.5`
   - `fliplr=0.0` **bắt buộc**: lật ngang làm hỏng chữ số trên biển.
   - `imgsz=960`: app gửi cả bề ngang khung vào YOLO khi model có biển báo, nên biển xa cần độ phân giải cao hơn 640.
   - `hsv_v` tăng sáng/tối (nắng gắt, đèn pha rọi biển ban đêm); có thể thêm ảnh mờ do chuyển động bằng Albumentations.
5. **Xuất ONNX** `yolo export model=best.pt format=onnx imgsz=960 opset=12`, lượng tử INT8 cho NPU (QNN) với khoảng 200 ảnh mẫu, đặt vào `assets` thay model cũ.
6. **Tạo `assets/yolo_labels.txt`**, mỗi dòng một tên lớp đúng thứ tự trong `data.yaml`. Khi file có lớp `speed_limit_*`, app tự chuyển YOLO sang nhìn cả bề ngang khung (từ mép trên tới capo) để bắt biển ở lề phải.

Giới hạn tốc độ đọc từ biển được **giữ** tới khi gặp biển mới, hoặc khi rẽ sang đường khác (tên đường VietMap đổi), tối đa 30 phút.

### Giả lập
https://claude.ai/artifact/Pz5ZwAYxFnRDrrETmEKpkh có thêm: Tình huống (bình thường / xe cắt đầu / xe trước đã chạy), Âm báo (tít / giọng đọc, hiện câu sẽ đọc), Hiển thị (camera / phản chiếu kính lái), dòng tên đường thứ 4, và Chỉ đường VietMap (rẽ phải / vòng xuyến / đến nơi, kéo thanh khoảng cách để nghe câu đọc).

### Chỉ đường VietMap (file mới `VietMapNav.kt`, sửa `Hud.kt` và `MainActivity.kt`)
- Bấm nút **➤** cạnh ⚙ (hoặc Cài đặt › Bản đồ VietMap › Chỉ đường). Chọn Ô tô / Xe máy, gõ điểm đến, bấm Tìm, chạm vào kết quả là bắt đầu dẫn đường. Nút ➤ chuyển xanh khi đang dẫn đường; mở lại để xem quãng đường còn lại hoặc **Dừng chỉ đường**.
- Trên HUD: ô hướng rẽ bên trái (dùng lại hoạt ảnh rẽ có sẵn, kèm khoảng cách tới chỗ rẽ và tên đường sắp vào), bản đồ nhỏ dưới đồng hồ tốc độ (tuyến luôn quay lên, chấm vàng là chỗ rẽ, dưới là km và phút còn lại). Tắt bản đồ nhỏ ở Cài đặt › Bản đồ nhỏ. Khi đang chỉ đường, hướng rẽ VietMap được ưu tiên hơn Waze.
- Giọng đọc: một câu khi còn khoảng 300 m (800 m nếu trên 60 km/h), ví dụ "Còn 300 mét, rẽ phải vào Nguyễn Huệ", rồi một câu ngắn ngay trước chỗ rẽ. Chế độ tiếng tít thì kêu hai tiếng nhẹ ngay trước chỗ rẽ.
- Đi lệch tuyến hơn 45 m quá 5 giây thì app tự tính lại đường (tối đa 30 giây một lần). Đến cách đích 25 m thì báo "Đã đến nơi" và tự tắt sau 15 giây.
- Lượt gọi API: mỗi lần tìm là 1 lượt Autocomplete, chọn kết quả là 1 lượt Place, mỗi lần tính đường là 1 lượt Route. Bám tuyến theo GPS không tốn lượt nào.
- App gọi bản v4 của VietMap, nếu key chưa mở v4 thì tự thử v3. Mình chưa gọi thử được bằng key của bạn vì máy chủ ở đây bị chặn tới maps.vietmap.vn. Nếu báo lỗi, ghi chú lỗi (ví dụ "HTTP 403") hiện ngay trong màn hình Chỉ đường.

## Chuyển sang HERE (20/10): bỏ VietMap và Waze

**File thay đổi:** thêm `HereGeo.kt`, `HereNav.kt`; sửa `MainActivity.kt`, `Hud.kt`.
**Xoá khỏi project:** `VietMap.kt`, `VietMapNav.kt`. `WazeModClient.kt` không còn dùng, xoá được.

- **Cài đặt › Bản đồ HERE:** dán API key HERE (chỉ để test). Bản thương mại bỏ ô này, app gọi qua máy chủ riêng giữ key.
- **Tên đường + giới hạn tốc độ:** một lượt gọi HERE Reverse Geocoding (`showNavAttributes=speedLimits`) lấy cả hai. Nếu HERE từ chối tham số này, app tự gọi lại chỉ lấy tên đường.
- **Thứ tự giới hạn tốc độ:** biển camera đọc được → HERE (theo tuyến nếu đang chỉ đường, không thì lần tra gần nhất).
- **Tiết kiệm lượt gọi:**
  - không tra khi chạy dưới 10 km/h
  - chỉ tra khi đi thêm 150 m (400 m nếu trên 70 km/h) hoặc vừa rẽ (> 35°)
  - mỗi lần tra cách nhau ít nhất 8 s
  - đang chỉ đường thì dùng tên đường + tốc độ của cả tuyến tải một lần (`spans`), không tra thêm
- **Chỉ đường:** HERE Autosuggest (trả luôn toạ độ, 1 lượt) + Routing v8. Xe máy dùng chế độ `scooter` của HERE. Khung rẽ, bản đồ nhỏ và giọng đọc giữ nguyên.
- **Mất khi bỏ Waze:** cảnh báo kẹt xe phía trước và camera/CSGT.
- **Kiểm tra trên máy:** Cài đặt › Bản đồ HERE hiện dòng "HERE: <tên đường>, giới hạn … km/h · N lần tra" sau khi xe chạy ~150 m.

## Bố cục HUD mới + Chạy thử tuyến (20/10)

**File thay đổi:** `Hud.kt`, `TurnGuide.kt`, `HereNav.kt`, `MainActivity.kt`, `LocationFallback.kt`.

- **Góc phải, dưới nút ⚙:** 3 icon cùng cỡ gồm chế độ đường (Trong phố / Cao tốc / Trời mưa), GPS và CAMERA. GPS và CAMERA đổi màu theo trạng thái:
  - GPS: xanh = tốt, cam = yếu (fix cũ > 3 s hoặc sai số > 25 m), đỏ = mất (không có fix 10 s, hoặc đang phát video)
  - CAMERA: xanh = tốt, cam = chậm (khung cách nhau > 150 ms hoặc kết quả nhận diện cũ > 0,5 s), đỏ = không có kết quả nhận diện > 2 s
- **Tên đường không còn hiện trên HUD.**
- **Dưới, giữa tâm làn:** biển giới hạn tốc độ và số tốc độ cùng chiều cao, cả cụm canh giữa (bỏ đồng hồ tròn). Biển STOP / đèn vừa thấy hiện bên phải cụm này.
- **Ô bản đồ chỉ đường bên trái, ô hướng rẽ bên phải:** khung trong suốt viền xanh sáng, đáy ngang cụm tốc độ.
- **Khung vật cản:** bo góc, viền sáng. Vạch làn hai bên màu xanh sáng.
- Quầng sáng vẽ bằng nét rộng trong mờ, không dùng blur, để không tốn thêm CPU / nhiệt.
- **Chạy thử tuyến:**
  1. Vào Chỉ đường (➤) và bật "Chạy thử tuyến".
  2. Tìm và chọn điểm A, rồi tìm và chọn điểm B.
  3. App gọi HERE lấy tuyến thật, sau đó xe ảo chạy dọc tuyến thay GPS điện thoại: tốc độ theo biển của đoạn đường (không có thì 40 km/h), chậm còn 25 km/h gần chỗ rẽ.
  - HUD hiện hướng rẽ, giọng đọc, bản đồ, giới hạn tốc độ HERE như chạy thật.
  - Cảnh báo va chạm lúc này chỉ dùng tốc độ camera, nên để máy yên trên bàn không bị kêu.
  - Bấm "Dừng chỉ đường" để tắt xe ảo.

## Giảm nhiệt bằng GPU (20/10 tối)
File sửa: `MainActivity.kt`, `CameraSource.kt`.
- Nhật ký nhiệt cho thấy CPU nóng 74–89°C khi đứng yên dù NPU chỉ mất 35–55 ms: thủ phạm là CPU đổi màu YUV→RGB và vẽ HUD lên bitmap 1024 px ở mọi khung.
- Giờ hình camera điện thoại đi thẳng vào GPU (TextureView). CPU chỉ đổi màu 4 lần/giây cho ảnh cảnh báo / ảnh huấn luyện.
- HUD được ghi thành Picture rồi vẽ bằng GPU trên một lớp phủ, áp dụng cho mọi nguồn (cả video, USB, Wi‑Fi).
- Cài đặt › Hiệu năng › **Hình camera qua GPU (mát máy)**: bật mặc định. Nếu hình bị xoay sai, méo hoặc đen thì tắt đi và báo lại.
- HUD có thể trễ hơn hình camera 1–3 khung (vài chục ms), bình thường.
- Test nhiệt: rút sạc, tắt "Thu ảnh huấn luyện", để chạy 20 phút rồi gửi file trong `files/thermal/`.

## Chế độ chạy lâu 1–3 tiếng (20/10 tối)
File sửa: `MainActivity.kt`, `ThermalLog.kt`.
- NPU (supercombo) không chạy 20 lần/giây liên tục nữa: thường 15, trên 60 km/h 20, máy ấm (mức 2–3) 10, đứng yên hoặc rất nóng 5.
- Khi có nguy cơ va chạm (viền vàng/đỏ), NPU tự lên 20 lần/giây trong 3 giây.
- YOLO tính theo lần/giây: thường 7, mức 2 là 4, mức 3 trở lên 2,5. Không tắt hẳn vì YOLO là nguồn cảnh báo xe cắt đầu / người đi bộ.
- Thêm ngưỡng nhiệt CPU: từ 70°C lên mức 2, từ 78°C lên mức 3. Mức 3 được giữ tới khi CPU dưới 72°C liền 30 giây.
- Từ mức 2 trở lên, app tạm ngừng "Thu ảnh huấn luyện".
- Ở mức 3, app nhắc bằng giọng nói "Máy đang nóng, hãy bật điều hoà hướng vào điện thoại" (đang sạc thì thêm "dùng sạc chậm"), tối đa 10 phút một lần.
- Nhật ký nhiệt: cột `yolo_hz` và `npu_hz` thay cho hai cột cũ.

## Bố cục HUD 21/10 + nền bản đồ HERE
- Hàng Trong phố / GPS / Camera nằm cùng hàng với đồng hồ. Góc phải dưới nút ⚙ để trống cho icon cảnh báo trên đường (chưa có nguồn dữ liệu).
- Biển giới hạn tốc độ nằm bên phải số khoảng cách; biển STOP / đèn giao thông camera vừa thấy hiện ngay dưới nó 4 giây.
- Tốc độ chữ đậm hơn, canh giữa thẳng cột với khoảng cách, "km/h" bên phải số, sát vạch capo.
- Ô bản đồ chỉ đường có nền bản đồ thật của HERE (kiểu đêm, nhuộm xanh), quay theo hướng đi. Mỗi ô ảnh 256 px tải một lần (tính một lượt gọi HERE), app giữ 48 ô gần nhất trong bộ nhớ. Không có key thì vẫn là nền tối như cũ.
