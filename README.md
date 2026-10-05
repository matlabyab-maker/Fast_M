# Fast_M

برنامهٔ اندرویدی کنترل موس از طریق گوشی دوم. **یک APK مشترک** روی هر دو گوشی نصب می‌شود؛ در یکی نقش «دستگاه هدف / موس» و در دیگری نقش «کنترلر / ریموت» انتخاب می‌شود. ارتباط از طریق Wi-Fi یا هات‌اسپات محلی است و به اینترنت نیاز ندارد.

## نقش‌ها
- **گوشی موس:** نمایش IP و پذیرش ارتباط مستقیم و اجرای فرمان‌ها با Accessibility.
- **گوشی ریموت:** وارد کردن IP و ارسال حرکت نشانگر از میدان لمس.

## چیدمان ریموت
- ردیف بالایی: `Back`، `Drag بالا` و `Drag پایین`.
- بخش پایینی: فقط میدان بزرگ لمس برای حرکت نشانگر؛ هیچ دکمهٔ کلیک یا اسکرول داخل میدان نیست.
- فرمان Back از مسیر `GLOBAL_ACTION_BACK` اجرا می‌شود و از فرمان کلیک چپ جداست.
- Drag بالا و پایین به‌صورت ژست کشیدن عمودی مستقل در نقطهٔ فعلی نشانگر ارسال می‌شوند.

## اتصال
1. هر دو گوشی را به یک شبکهٔ Wi-Fi یا هات‌اسپات وصل کنید.
2. روی گوشی موس، نقش دستگاه هدف را انتخاب کنید و Accessibility برنامه را از تنظیمات اندروید فعال کنید.
3. مجوزها را طبق راهنمای برنامه بدهید و سرویس پذیرش اتصال را آغاز کنید.
4. IP گوشی موس را در گوشی ریموت وارد و دکمهٔ اتصال را بزنید؛ رمز یا کد اتصال لازم نیست.

ارتباط TCP محلی روی پورت `47821` انجام می‌شود. در این نسخه احراز هویت با رمز حذف شده است؛ بنابراین هر دستگاهی که به همان شبکهٔ محلی دسترسی داشته باشد و IP گوشی موس را بداند، می‌تواند برای کنترل متصل شود. فقط از شبکهٔ مورد اعتماد استفاده کنید.

## ساخت
پروژه را در Android Studio باز کنید یا به مخزن GitHub با نام `Fast_M` پوش کنید. فایل `.github/workflows/android.yml` برای ساخت APK آزمایشی با GitHub Actions تنظیم شده است.

## وضعیت تحویل
این بسته شامل سورس پروژه و گردش‌کار ساخت APK است. در این محیط Android SDK و Gradle نصب نبودند؛ بنابراین APK ساخته و روی گوشی واقعی آزمایش نشده است. پس از Push به GitHub، نتیجهٔ workflow و سپس عملکرد دو گوشی باید بررسی شود.


## Reference-image remote UI
The controller screen now displays the two supplied reference images as full-screen backgrounds. Transparent hit areas are placed over the pictured controls; Menu 1/Menu 2 switch between the two layouts. Page Up/Down send scroll commands, Back sends the Android Back command, Left Click sends a click, and Drag sends relative pointer movement. The Copy/Past and Point Zoom regions are visual hit areas only in this build because their receiver-side functions were not present in the supplied command handler. The package has not been built or tested on physical phones.


## اصلاح اتصال کنترلر و تصاویر زمینه
- کنترلر اکنون پیش از نمایش منو IP گوشی هدف را می‌پرسد و اتصال TCP به درگاه 47821 را برقرار می‌کند؛ قبلاً showController مستقیماً منو را نشان می‌داد و اصلاً connectToTarget را صدا نمی‌زد، پس دکمه‌ها فرمانی ارسال نمی‌کردند.
- دو تصویر اصلی منو در drawable-nodpi فشرده شده‌اند: remote_menu1.jpg و remote_menu2.jpg هرکدام در محدودهٔ 50 تا 60 KB.
- Point Zoom، Copy و Past هنوز در RemoteServerService پیاده‌سازی نشده‌اند؛ سایر فرمان‌های Back، Scroll، Left Click و Move در گیرنده وجود دارند.
