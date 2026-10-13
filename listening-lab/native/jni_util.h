#pragma once
#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>
#include <algorithm>
inline std::string utf(JNIEnv* e,jstring value){const char* p=e->GetStringUTFChars(value,nullptr);std::string s=p?p:"";if(p)e->ReleaseStringUTFChars(value,p);return s;}
inline jbyteArray bytes(JNIEnv* e,const std::string& s){auto a=e->NewByteArray(s.size());if(a)e->SetByteArrayRegion(a,0,s.size(),reinterpret_cast<const jbyte*>(s.data()));return a;}
inline void error(JNIEnv* e,const std::string& text){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),text.c_str());}
inline std::string quote(const char* p){std::ostringstream out;out<<'"';for(unsigned char c:std::string(p?p:"")){if(c=='"'||c=='\\')out<<'\\'<<c;else if(c=='\n')out<<"\\n";else if(c=='\r')out<<"\\r";else if(c=='\t')out<<"\\t";else if(c<32)out<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<(int)c<<std::dec;else out<<c;}out<<'"';return out.str();}
