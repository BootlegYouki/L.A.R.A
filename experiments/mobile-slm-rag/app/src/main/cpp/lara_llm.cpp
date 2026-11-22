// Thin JNI layer over llama.cpp b11938 for the throwaway on-device SLM experiment: load, render the chat template,
// stream tokens, report timings. One model and one context at a time.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <sampling.h>
#include <thread>
#include <unistd.h>
#include "chat.h"
#include "common.h"
#include "llama.h"

#define TAG "lara-llm"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static common_chat_templates_ptr g_tmpl;
static std::atomic<bool> g_cancel{false};
// prompt tokens, prompt ms, generated tokens, generated ms
static double g_stats[4] = {0, 0, 0, 0};

static std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Length of the longest prefix that does not end inside a multi-byte UTF-8 character.
static size_t complete_utf8_len(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4 && ((unsigned char) s[i - 1] & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return n;
    unsigned char lead = (unsigned char) s[i - 1];
    int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return (need > back + 1) ? i - 1 : n;
}

// ggml prints GGML_ASSERT / abort reasons to stderr, which Android drops; forward them to logcat.
static void redirect_stderr() {
    static int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], 2);
    std::thread([] {
        char buf[1024];
        ssize_t n;
        while ((n = read(fds[0], buf, sizeof(buf) - 1)) > 0) { buf[n] = 0; __android_log_print(ANDROID_LOG_ERROR, "lara-stderr", "%s", buf); }
    }).detach();
}

extern "C" JNIEXPORT void JNICALL
Java_org_lara_experiment_llm_Llama_nInit(JNIEnv *env, jobject, jstring libDir) {
    redirect_stderr();
    llama_log_set([](ggml_log_level lvl, const char *text, void *) {
        __android_log_print(lvl >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, "%s", text);
    }, nullptr);
    ggml_backend_load_all_from_path(str(env, libDir).c_str());
    llama_backend_init();
}

extern "C" JNIEXPORT jint JNICALL
Java_org_lara_experiment_llm_Llama_nLoad(JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads) {
    auto mp = llama_model_default_params();
    g_model = llama_model_load_from_file(str(env, path).c_str(), mp);
    if (!g_model) return 1;
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return 2; }
    g_tmpl = common_chat_templates_init(g_model, "");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_lara_experiment_llm_Llama_nUnload(JNIEnv *, jobject) {
    g_tmpl.reset();
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr;
    g_model = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_lara_experiment_llm_Llama_nCancel(JNIEnv *, jobject) { g_cancel = true; }

extern "C" JNIEXPORT jint JNICALL
Java_org_lara_experiment_llm_Llama_nTokenCount(JNIEnv *env, jobject, jstring text) {
    if (!g_model) return -1;
    auto t = str(env, text);
    return (jint) -llama_tokenize(llama_model_get_vocab(g_model), t.c_str(), (int32_t) t.size(), nullptr, 0, false, true);
}

// The model's own chat template (Jinja), thinking switched off for Qwen3-style templates, generation prompt appended.
extern "C" JNIEXPORT jstring JNICALL
Java_org_lara_experiment_llm_Llama_nRenderChat(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents) {
    LOGi("render: begin");
    common_chat_templates_inputs in;
    in.use_jinja = true;
    in.enable_thinking = false;
    in.add_generation_prompt = true;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = str(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = str(env, (jstring) env->GetObjectArrayElement(contents, i));
        in.messages.push_back(m);
    }
    try {
        auto out = common_chat_templates_apply(g_tmpl.get(), in);
        LOGi("render: ok %zu chars", out.prompt.str().size());
        return env->NewStringUTF(out.prompt.str().c_str());
    } catch (...) { // a template error must not abort the app
        LOGe("chat template failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "chat template failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_lara_experiment_llm_Llama_nGenerate(JNIEnv *env, jobject, jstring jprompt, jint maxTokens, jobject sink) {
    g_cancel = false;
    LOGi("generate: begin");
    g_stats[0] = g_stats[1] = g_stats[2] = g_stats[3] = 0;
    auto prompt = str(env, jprompt);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    // Gemma-style templates already write <bos>; do not add a second one.
    const char *bos = llama_vocab_get_text(vocab, llama_vocab_bos(vocab));
    bool add_special = !(bos && prompt.rfind(bos, 0) == 0);
    auto toks = common_tokenize(vocab, prompt, add_special, true);
    int n_ctx = (int) llama_n_ctx(g_ctx);
    if ((int) toks.size() + 8 >= n_ctx) { LOGe("prompt %d tokens does not fit n_ctx %d", (int) toks.size(), n_ctx); return 3; }
    LOGi("generate: %d tokens, add_special=%d", (int) toks.size(), (int) add_special);
    llama_memory_clear(llama_get_memory(g_ctx), true);
    LOGi("generate: memory cleared");

    // same defaults as llama-server in the desktop experiment (temp 0.8, top-k 40, top-p 0.95, min-p 0.05), fixed seed
    common_params_sampling sp;
    sp.seed = 42;
    auto *smpl = common_sampler_init(g_model, sp);
    LOGi("generate: sampler ready");

    jclass cls = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");

    int64_t t0 = ggml_time_us();
    const int B = 512;
    for (size_t i = 0; i < toks.size(); i += B) {
        int n = (int) std::min<size_t>(B, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(g_ctx, b) != 0) { common_sampler_free(smpl); return 4; }
    }
    int64_t t1 = ggml_time_us();
    LOGi("generate: prompt decoded");
    g_stats[0] = (double) toks.size();
    g_stats[1] = (t1 - t0) / 1000.0;

    std::string pending;
    int gen = 0, rc = 0;
    int pos = (int) toks.size();
    while (gen < maxTokens && pos + 1 < n_ctx && !g_cancel) {
        llama_token id = common_sampler_sample(smpl, g_ctx, -1);
        common_sampler_accept(smpl, id, true);
        if (llama_vocab_is_eog(vocab, id)) break;
        pending += common_token_to_piece(vocab, id);
        gen++;
        size_t ok = complete_utf8_len(pending);
        if (ok > 0) {
            jstring js = env->NewStringUTF(pending.substr(0, ok).c_str());
            jboolean go = env->CallBooleanMethod(sink, onToken, js);
            env->DeleteLocalRef(js);
            pending.erase(0, ok);
            if (!go) break;
        }
        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, b) != 0) { rc = 5; break; }
        pos++;
    }
    int64_t t2 = ggml_time_us();
    g_stats[2] = gen;
    g_stats[3] = (t2 - t1) / 1000.0;
    common_sampler_free(smpl);
    return rc;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_org_lara_experiment_llm_Llama_nStats(JNIEnv *env, jobject) {
    jdoubleArray a = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(a, 0, 4, g_stats);
    return a;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_lara_experiment_llm_Llama_nSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
