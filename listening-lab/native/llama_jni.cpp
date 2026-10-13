#include "jni_util.h"
#include "llama.h"
#include <atomic>
#include <vector>
struct Translator{llama_model* model;llama_context* ctx;};
static std::atomic<bool> cancelled(false);
static bool abort_inference(void*){return cancelled.load();}
extern "C" JNIEXPORT void JNICALL Java_cn_buting_lab_TranslationEngine_cancel(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT jlong JNICALL Java_cn_buting_lab_TranslationEngine_open(JNIEnv* e,jclass,jstring path,jint threads){
 try{
 cancelled=false;llama_backend_init();auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;
 auto* model=llama_model_load_from_file(utf(e,path).c_str(),mp);if(!model){error(e,"Translation model could not be loaded");return 0;}
 auto cp=llama_context_default_params();cp.n_ctx=1536;cp.n_batch=1024;cp.n_ubatch=128;cp.n_threads=std::max(1,std::min(threads,4));cp.n_threads_batch=cp.n_threads;cp.abort_callback=abort_inference;cp.abort_callback_data=nullptr;
 auto* ctx=llama_init_from_model(model,cp);if(!ctx){llama_model_free(model);error(e,"Translation context could not be allocated");return 0;}return reinterpret_cast<jlong>(new Translator{model,ctx});
 }catch(const std::exception& ex){error(e,ex.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_cn_buting_lab_TranslationEngine_close(JNIEnv*,jclass,jlong handle){auto* t=reinterpret_cast<Translator*>(handle);if(t){llama_free(t->ctx);llama_model_free(t->model);delete t;}}
extern "C" JNIEXPORT jbyteArray JNICALL Java_cn_buting_lab_TranslationEngine_translate(JNIEnv* e,jclass,jlong handle,jstring text){
 try{
 cancelled=false;auto* t=reinterpret_cast<Translator*>(handle);if(!t){error(e,"Translation engine is not loaded");return nullptr;}
 std::string source=utf(e,text);
 // Source is data inside a fixed translation task; neutralize chat delimiter injection.
 for(size_t at=0;(at=source.find("<|",at))!=std::string::npos;at+=2)source.replace(at,2,"< ");
 const std::string prompt="<|im_start|>system\nYou are an English to Simplified Chinese translator. Translate the user's English text faithfully. Output only the Chinese translation, without explanations or added facts.<|im_end|>\n<|im_start|>user\n"+source+"<|im_end|>\n<|im_start|>assistant\n";
 auto* vocab=llama_model_get_vocab(t->model);int n=-llama_tokenize(vocab,prompt.c_str(),prompt.size(),nullptr,0,true,true);if(n<=0||n>1000){error(e,"Text is too long for this test model");return nullptr;}
 std::vector<llama_token> tokens(n);n=llama_tokenize(vocab,prompt.c_str(),prompt.size(),tokens.data(),tokens.size(),true,true);tokens.resize(n);
 llama_memory_clear(llama_get_memory(t->ctx),true);
 if(llama_decode(t->ctx,llama_batch_get_one(tokens.data(),tokens.size()))!=0){error(e,cancelled?"CANCELLED":"Translation prompt failed");return nullptr;}
 auto* sampler=llama_sampler_init_greedy();std::string output;bool finished=false;
 for(int i=0;i<320;i++){
   if(cancelled){llama_sampler_free(sampler);error(e,"CANCELLED");return nullptr;}
   auto token=llama_sampler_sample(sampler,t->ctx,-1);llama_sampler_accept(sampler,token);
   if(llama_vocab_is_eog(vocab,token)){finished=true;break;}
   char buffer[256];int size=llama_token_to_piece(vocab,token,buffer,sizeof(buffer),0,false);
   if(size>0)output.append(buffer,size);
   else if(size<0){std::vector<char> large(-size);size=llama_token_to_piece(vocab,token,large.data(),large.size(),0,false);if(size>0)output.append(large.data(),size);}
   if(llama_decode(t->ctx,llama_batch_get_one(&token,1))!=0){llama_sampler_free(sampler);error(e,cancelled?"CANCELLED":"Translation generation failed");return nullptr;}
 }
 llama_sampler_free(sampler);if(!finished){error(e,"Translation exceeded token limit; edit or split the sentence");return nullptr;}
 return bytes(e,output);
 }catch(const std::exception& ex){error(e,ex.what());return nullptr;}
}
