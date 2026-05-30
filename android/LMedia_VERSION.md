# LMedia Version

当前 `lmedia` 目录按源码方式放入 Android 工程，不作为独立 Git 子模块提交。

来源：

```text
https://gitee.com/lalilu/lmedia.git
commit b6a2944
```

这版和当前 LMusic 源码更匹配，包含 `FileSystemScanner`、`LMediaSp` 和基于文件描述符的 TagLib 接口。

同时把原本的子模块也按源码方式放进来了：

```text
https://gitee.com/lalilu/taglib.git
commit 9a026976ae366b16605b4a05ace59e99bcabd3d7

https://github.com/nemtrif/utfcpp.git
commit df857efc5bbc2aa84012d865f7d7e9cccdc08562
```
