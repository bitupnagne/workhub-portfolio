# Third-party notices

These notices apply to the named third-party resources. They do not grant a license to the application as a whole.

| Resource | Source and license |
| --- | --- |
| OTP UI font: Pretendard Variable | [Official Pretendard](https://github.com/orioncactus/pretendard), SIL Open Font License 1.1. Complete original notice: [Pretendard-OFL.txt](src/main/resources/META-INF/licenses/Pretendard-OFL.txt). The font data is unchanged. |
| Material Icons artwork | [Google Material Design Icons](https://github.com/google/material-design-icons), Apache License 2.0. Existing notice: [google-material-icons-LICENSE.txt](src/main/resources/META-INF/licenses/google-material-icons-LICENSE.txt). |
| TOAST UI Editor 3.2.2 | [NHN TOAST UI Editor](https://github.com/nhn/tui.editor/tree/editor%403.2.2), MIT. Original [license](src/main/resources/META-INF/licenses/toastui-editor-LICENSE.txt) and bundle notices are retained. |
| DOMPurify 2.3.3 in the editor bundle | [Cure53 DOMPurify](https://github.com/cure53/DOMPurify/tree/2.3.3), upstream Apache/MPL dual license. Full [license](src/main/resources/META-INF/licenses/DOMPurify-LICENSE.txt) is retained. |
| Quill 2.0.3 | [Quill](https://github.com/slab/quill/tree/v2.0.3), BSD 3-Clause. Original [license](src/main/resources/META-INF/licenses/quill-LICENSE.txt) and bundled headers are retained. |
| Lodash in bundled editor code | [Lodash](https://github.com/lodash/lodash), MIT and retained Underscore attribution. Full [license](src/main/resources/META-INF/licenses/lodash-LICENSE.txt) is retained. |
| Maven Wrapper scripts | Original Apache Maven Wrapper notices remain in `mvnw` and `mvnw.cmd`. The downloaded wrapper JAR is not distributed here. |

One embedded PNG toolbar sprite in the editor stylesheet is packaged as `toastui-toolbar-icons.png` in this source edition. The stylesheet references it through a relative URL and the decoded image bytes are unchanged. All other bundled library code and attribution headers are preserved.

The OTP download QR images encode official Google Authenticator links to [Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2) and [Apple App Store](https://apps.apple.com/kr/app/google-authenticator/id388497605), without an account or enrollment secret.

Java dependencies are declared in `pom.xml` and downloaded at build time. Their licenses remain those of their publishers; compiled dependency JARs are not included in this source repository.
