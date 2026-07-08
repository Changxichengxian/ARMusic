
#include "taglibWrapper.h"
#include <vector>

using namespace std;

jstring toString(JNIEnv *env, TagLib::String str) {
    return env->NewStringUTF(str.toCString(true));
}

TagLib::String toTagString(JNIEnv *env, jstring value) {
    if (value == nullptr) return TagLib::String();

    const char *chars = env->GetStringUTFChars(value, nullptr);
    TagLib::String result(chars != nullptr ? chars : "", TagLib::String::Type::UTF8);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }

    return result;
}

unsigned int toUInt(const TagLib::String &value) {
    auto raw = value.to8Bit(true);
    try {
        return raw.empty() ? 0 : static_cast<unsigned int>(std::stoul(raw));
    } catch (...) {
        return 0;
    }
}

void replaceProperty(TagLib::PropertyMap &map, const char *key, const TagLib::String &value) {
    map.replace(key, TagLib::StringList(value));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_getLyricWithFD(JNIEnv *env, jobject thiz,
                                                     jint file_descriptor) {
    TagLib::FileStream fileStream(file_descriptor, true);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull()) return env->NewStringUTF("File is not supported");

    auto map = fileRef.tag()->properties();

    auto lyrics = map["LYRICS"];
    if (lyrics.size() > 0 && lyrics[0].size() > 0) {
        return env->NewStringUTF(lyrics[0].toCString(true));
    }
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_writeLyricInto(JNIEnv *env, jobject thiz,
                                                     jint file_descriptor,
                                                     jstring lyric) {
    TagLib::FileStream fileStream(file_descriptor, true);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull()) return JNI_FALSE;

    auto lyricStr = env->GetStringUTFChars(lyric, nullptr);

    auto map = fileRef.file()->properties();
    map.replace("LYRICS", TagLib::String(lyricStr, TagLib::String::Type::UTF8));
    fileRef.file()->setProperties(map);
    env->ReleaseStringUTFChars(lyric, lyricStr);

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_writeMetadataWithFD(JNIEnv *env, jobject thiz,
                                                          jint file_descriptor,
                                                          jstring title,
                                                          jstring album,
                                                          jstring artist,
                                                          jstring album_artist,
                                                          jstring composer,
                                                          jstring lyricist,
                                                          jstring comment,
                                                          jstring genre,
                                                          jstring track,
                                                          jstring disc,
                                                          jstring date,
                                                          jstring work,
                                                          jstring same_song_group,
                                                          jstring lyric) {
    TagLib::FileStream fileStream(file_descriptor, false);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull() || fileRef.tag() == nullptr || fileRef.file() == nullptr) return JNI_FALSE;

    auto titleStr = toTagString(env, title);
    auto albumStr = toTagString(env, album);
    auto artistStr = toTagString(env, artist);
    auto albumArtistStr = toTagString(env, album_artist);
    auto composerStr = toTagString(env, composer);
    auto lyricistStr = toTagString(env, lyricist);
    auto commentStr = toTagString(env, comment);
    auto genreStr = toTagString(env, genre);
    auto trackStr = toTagString(env, track);
    auto discStr = toTagString(env, disc);
    auto dateStr = toTagString(env, date);
    auto workStr = toTagString(env, work);
    auto sameSongGroupStr = toTagString(env, same_song_group);
    auto lyricStr = toTagString(env, lyric);

    auto tag = fileRef.tag();
    tag->setTitle(titleStr);
    tag->setAlbum(albumStr);
    tag->setArtist(artistStr);
    tag->setComment(commentStr);
    tag->setGenre(genreStr);
    tag->setTrack(toUInt(trackStr));

    auto map = fileRef.file()->properties();
    replaceProperty(map, "TITLE", titleStr);
    replaceProperty(map, "ALBUM", albumStr);
    replaceProperty(map, "ARTIST", artistStr);
    replaceProperty(map, "ALBUMARTIST", albumArtistStr);
    replaceProperty(map, "COMPOSER", composerStr);
    replaceProperty(map, "LYRICIST", lyricistStr);
    replaceProperty(map, "COMMENT", commentStr);
    replaceProperty(map, "GENRE", genreStr);
    replaceProperty(map, "TRACKNUMBER", trackStr);
    replaceProperty(map, "DISCNUMBER", discStr);
    replaceProperty(map, "DATE", dateStr);
    replaceProperty(map, "WORK", workStr);
    replaceProperty(map, "ARMUSIC_GROUP", sameSongGroupStr);
    replaceProperty(map, "LYRICS", lyricStr);
    fileRef.file()->setProperties(map);

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_writeWorkWithFD(JNIEnv *env, jobject thiz,
                                                      jint file_descriptor,
                                                      jstring work) {
    TagLib::FileStream fileStream(file_descriptor, false);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull() || fileRef.file() == nullptr) return JNI_FALSE;

    auto map = fileRef.file()->properties();
    replaceProperty(map, "WORK", toTagString(env, work));
    fileRef.file()->setProperties(map);

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_removeCoverWithFD(JNIEnv *env, jobject thiz,
                                                        jint file_descriptor) {
    TagLib::FileStream fileStream(file_descriptor, false);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull() || fileRef.file() == nullptr) return JNI_FALSE;

    if (!fileRef.setComplexProperties("PICTURE", {})) return JNI_FALSE;

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_writeCoverWithFD(JNIEnv *env, jobject thiz,
                                                       jint file_descriptor,
                                                       jbyteArray cover,
                                                       jstring mime_type) {
    if (cover == nullptr) return JNI_FALSE;

    auto length = env->GetArrayLength(cover);
    if (length <= 0) return JNI_FALSE;

    auto data = env->GetByteArrayElements(cover, nullptr);
    if (data == nullptr) return JNI_FALSE;

    TagLib::ByteVector coverData(reinterpret_cast<const char *>(data),
                                 static_cast<unsigned int>(length));
    env->ReleaseByteArrayElements(cover, data, JNI_ABORT);

    TagLib::FileStream fileStream(file_descriptor, false);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull() || fileRef.file() == nullptr) return JNI_FALSE;

    TagLib::VariantMap picture;
    picture.insert("data", coverData);
    picture.insert("pictureType", TagLib::String("Front Cover"));
    picture.insert("mimeType", toTagString(env, mime_type));
    picture.insert("description", TagLib::String("Cover"));

    TagLib::List<TagLib::VariantMap> pictures;
    pictures.append(picture);

    if (!fileRef.setComplexProperties("PICTURE", pictures)) return JNI_FALSE;

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_writeCoversWithFD(JNIEnv *env, jobject thiz,
                                                        jint file_descriptor,
                                                        jobjectArray covers,
                                                        jobjectArray mime_types) {
    if (covers == nullptr) return JNI_FALSE;

    auto count = env->GetArrayLength(covers);
    auto mimeCount = mime_types != nullptr ? env->GetArrayLength(mime_types) : 0;
    if (count <= 0) return JNI_FALSE;

    TagLib::List<TagLib::VariantMap> pictures;
    for (jsize i = 0; i < count; i++) {
        auto cover = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(covers, i));
        if (cover == nullptr) continue;

        auto length = env->GetArrayLength(cover);
        if (length <= 0) {
            env->DeleteLocalRef(cover);
            continue;
        }

        auto data = env->GetByteArrayElements(cover, nullptr);
        if (data == nullptr) {
            env->DeleteLocalRef(cover);
            continue;
        }

        TagLib::ByteVector coverData(reinterpret_cast<const char *>(data),
                                     static_cast<unsigned int>(length));
        env->ReleaseByteArrayElements(cover, data, JNI_ABORT);
        env->DeleteLocalRef(cover);

        TagLib::String mimeType("image/jpeg");
        if (i < mimeCount) {
            auto mime = reinterpret_cast<jstring>(env->GetObjectArrayElement(mime_types, i));
            if (mime != nullptr) {
                mimeType = toTagString(env, mime);
                env->DeleteLocalRef(mime);
            }
        }

        TagLib::VariantMap picture;
        picture.insert("data", coverData);
        picture.insert("pictureType", TagLib::String(i == 0 ? "Front Cover" : "Other"));
        picture.insert("mimeType", mimeType);
        picture.insert("description", TagLib::String("ARMusic Cover " + std::to_string(i + 1)));
        pictures.append(picture);
    }

    if (pictures.isEmpty()) return JNI_FALSE;

    TagLib::FileStream fileStream(file_descriptor, false);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull() || fileRef.file() == nullptr) return JNI_FALSE;

    if (!fileRef.setComplexProperties("PICTURE", pictures)) return JNI_FALSE;

    return fileRef.file()->save() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_retrieveMetadataWithFD(JNIEnv *env, jobject thiz,
                                                             jint file_descriptor) {
    TagLib::FileStream fileStream(file_descriptor, true);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull()) return nullptr;           // 文件读取失败，返回空

    auto tag = fileRef.tag();
    auto map = tag->properties();
    auto audioProperties = fileRef.audioProperties();

//    for (auto item = map.cbegin(); item != map.cend(); ++item) {
//        LOGE("[%s]\n", item->first.toCString(true));
//    }

    // 获取该文件信息
    struct stat fileStat;
    fstat(file_descriptor, &fileStat);
    auto dateAdded = (jlong) fileStat.st_ctim.tv_sec;
    auto dateModified = (jlong) fileStat.st_mtim.tv_sec;

    // TODO 针对部分格式TagLib无法完全正确解析部分数据，待完善TagLib的部分扩展
    // https://www.jthink.net/jaudiotagger/tagmapping.html
    auto title_str = toString(env, tag->title());
    auto album_str = toString(env, tag->album());
    auto artist_str = toString(env, tag->artist());
    auto comment_str = toString(env, tag->comment());
    auto duration = (jlong) audioProperties->lengthInMilliseconds();
    auto track_num = toString(env, to_string(tag->track()));
    auto disc_num = toString(env, map["DISCNUMBER"].toString());
    auto album_artist_str = toString(env, map["ALBUMARTIST"].toString());
    auto composer_str = toString(env, map["COMPOSER"].toString());
    auto lyricist_str = toString(env, map["LYRICIST"].toString());
    auto genre_str = toString(env, map["GENRE"].toString());
    auto date = toString(env, map["DATE"].toString());
    auto workValue = map["WORK"].toString();
    if (workValue.isEmpty()) {
        workValue = map["GROUPING"].toString();
    }
    auto work_str = toString(env, workValue);
    auto same_song_group = toString(env, map["ARMUSIC_GROUP"].toString());

    // 获取需要创建的jclass
    jclass metadata_class = env->FindClass("com/lalilu/lmedia/entity/Metadata");

    // 获取构造器方法ID
    jmethodID constructor = env->GetMethodID(metadata_class, "<init>",
                                             "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJ)V");

    // 创建对象传入并参数
    jobject metadata_obj_j = env->NewObject(
            metadata_class,
            constructor,
            title_str,
            album_str,
            artist_str,
            album_artist_str,
            composer_str,
            lyricist_str,
            comment_str,
            genre_str,
            track_num,
            disc_num,
            date,
            work_str,
            same_song_group,
            duration,
            dateAdded,
            dateModified
    );

    return metadata_obj_j;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_getPictureWithFD(JNIEnv *env, jobject thiz,
                                                       jint file_descriptor) {
    TagLib::FileStream fileStream(file_descriptor, true);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull()) return nullptr;           // 文件读取失败，返回空

    auto pictures = fileRef.complexProperties("PICTURE");
    if (pictures.isEmpty()) return nullptr;

    auto picture = pictures.front().value("data").toByteVector();
    auto length = static_cast<jint>(picture.size());

    jbyteArray bytes = env->NewByteArray(length);
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(picture.data()));

    return bytes;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_lalilu_lmedia_wrapper_Taglib_getPicturesWithFD(JNIEnv *env, jobject thiz,
                                                        jint file_descriptor) {
    TagLib::FileStream fileStream(file_descriptor, true);
    TagLib::FileRef fileRef(&fileStream, true, TagLib::AudioProperties::ReadStyle::Fast);
    if (fileRef.isNull()) return nullptr;

    auto pictures = fileRef.complexProperties("PICTURE");
    if (pictures.isEmpty()) return nullptr;

    std::vector<TagLib::ByteVector> bytesList;
    for (auto picture: pictures) {
        auto data = picture.value("data").toByteVector();
        if (!data.isEmpty()) {
            bytesList.push_back(data);
        }
    }
    if (bytesList.empty()) return nullptr;

    jclass byteArrayClass = env->FindClass("[B");
    auto result = env->NewObjectArray(static_cast<jsize>(bytesList.size()), byteArrayClass, nullptr);
    if (result == nullptr) return nullptr;

    for (jsize i = 0; i < static_cast<jsize>(bytesList.size()); i++) {
        auto picture = bytesList[static_cast<size_t>(i)];
        auto length = static_cast<jint>(picture.size());
        jbyteArray bytes = env->NewByteArray(length);
        if (bytes == nullptr) continue;

        env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(picture.data()));
        env->SetObjectArrayElement(result, i, bytes);
        env->DeleteLocalRef(bytes);
    }

    return result;
}
