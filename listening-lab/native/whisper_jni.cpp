#include "jni_util.h"
#include "whisper.h"
#include <atomic>
#include <vector>
static std::atomic<bool> cancelled(false);
static bool abort_inference(void*){return cancelled.load();}
extern "C" JNIEXPORT void JNICALL Java_cn_buting_lab_WhisperEngine_cancel(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT jlong JNICALL Java_cn_buting_lab_WhisperEngine_open(JNIEnv* e,jclass,jstring path){
 try{cancelled=false;auto p=whisper_context_default_params();p.use_gpu=false;p.flash_attn=false;p.dtw_token_timestamps=true;p.dtw_aheads_preset=WHISPER_AHEADS_BASE_EN;p.dtw_mem_size=128*1024*1024;
 auto* ctx=whisper_init_from_file_with_params(utf(e,path).c_str(),p);if(!ctx){error(e,"Whisper model could not be loaded");return 0;}return reinterpret_cast<jlong>(ctx);}catch(const std::exception& ex){error(e,ex.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_cn_buting_lab_WhisperEngine_close(JNIEnv*,jclass,jlong handle){if(handle)whisper_free(reinterpret_cast<whisper_context*>(handle));}
extern "C" JNIEXPORT jbyteArray JNICALL Java_cn_buting_lab_WhisperEngine_recognize(JNIEnv* e,jclass,jlong handle,jfloatArray pcm,jint threads){
 try{
 cancelled=false;auto* ctx=reinterpret_cast<whisper_context*>(handle);if(!ctx){error(e,"Whisper engine is not loaded");return nullptr;}
 std::vector<float> input(e->GetArrayLength(pcm));e->GetFloatArrayRegion(pcm,0,input.size(),input.data());
 auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);p.n_threads=std::max(1,std::min(threads,4));p.language="en";p.translate=false;p.no_context=true;p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;p.print_special=false;p.token_timestamps=true;p.split_on_word=true;p.suppress_blank=true;p.suppress_nst=true;p.temperature=0;p.temperature_inc=0;p.abort_callback=abort_inference;p.abort_callback_user_data=nullptr;
 const int result=whisper_full(ctx,p,input.data(),input.size());if(cancelled){error(e,"CANCELLED");return nullptr;}if(result!=0){error(e,"Whisper inference failed: "+std::to_string(result));return nullptr;}
 std::ostringstream out;out<<"[";bool first=true;
 for(int s=0;s<whisper_full_n_segments(ctx);s++){
   auto segment_start=whisper_full_get_segment_t0(ctx,s),segment_end=whisper_full_get_segment_t1(ctx,s);
   for(int t=0;t<whisper_full_n_tokens(ctx,s);t++){
     auto data=whisper_full_get_token_data(ctx,s,t);if(data.id>=whisper_token_eot(ctx))continue;
     if(!first)out<<",";first=false;
     out<<"{\"text\":"<<quote(whisper_full_get_token_text(ctx,s,t))<<",\"startMs\":"<<data.t0*10<<",\"endMs\":"<<data.t1*10<<",\"centerMs\":"<<data.t_dtw*10<<",\"segmentStartMs\":"<<segment_start*10<<",\"segmentEndMs\":"<<segment_end*10<<",\"probability\":"<<data.p<<"}";
   }
 }
 out<<"]";return bytes(e,out.str());
 }catch(const std::exception& ex){error(e,ex.what());return nullptr;}
}
