<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import {
  AlertTriangle,
  CalendarDays,
  CheckCircle2,
  Clipboard,
  Download,
  FileSpreadsheet,
  LoaderCircle,
  LogOut,
  Play,
  RefreshCw,
  UploadCloud
} from "lucide-vue-next";

import { ApiFailure, apiFetch } from "./api";
import type { AnalyzeResponse, ApiError, TaskEvent, TemplateMetadata } from "./types";

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const CODE_PATTERN = /^[0-9]{6}$/;
const DATE_PATTERN = /^\d{4}\.\d{2}\.\d{2}$/;

const authState = ref<"loading" | "anonymous" | "authenticated">("loading");
const password = ref("");
const loginBusy = ref(false);
const loginError = ref<ApiError | null>(null);

const fileInput = ref<HTMLInputElement | null>(null);
const startCalendar = ref<HTMLInputElement | null>(null);
const endCalendar = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const metadata = ref<TemplateMetadata | null>(null);
const analyzing = ref(false);
const processing = ref(false);
const codes = ref("");
const startDate = ref("");
const endDate = ref("");
const fillName = ref(false);
const fillIdealBuy = ref(false);
const fillIdealSell = ref(false);
const progress = ref(0);
const progressStage = ref("idle");
const progressMessage = ref("等待文件");
const operationError = ref<ApiError | null>(null);
const errorResult = ref("");
const copied = ref(false);
const downloadBlob = ref<Blob | null>(null);
const downloadFilename = ref("");
let eventSource: EventSource | null = null;
let blobUrl: string | null = null;

const parsedCodes = computed(() => {
  return codes.value.trim().split(/[\s,，;；]+/).filter(Boolean);
});

const codeError = computed(() => {
  if (!codes.value.trim()) return "至少输入一只股票代码";
  const invalid = parsedCodes.value.filter((code) => !CODE_PATTERN.test(code));
  if (invalid.length) return `代码必须为六位数字：${invalid.join("、")}`;
  const duplicate = parsedCodes.value.find((code, index, all) => all.indexOf(code) !== index);
  return duplicate ? `代码不能重复：${duplicate}` : "";
});

const dateError = computed(() => {
  if (!validDate(startDate.value) || !validDate(endDate.value)) {
    return "日期必须使用有效的 yyyy.MM.dd";
  }
  if (startDate.value > endDate.value) return "开始日期不能晚于结束日期";
  const periods = metadata.value?.periods ?? [];
  if (periods.length > 1) {
    const previous = periods[periods.length - 2];
    if (startDate.value <= previous.startDate || endDate.value <= previous.endDate) {
      return "开始和结束日期必须分别晚于上一时间段";
    }
  }
  return "";
});

const canProcess = computed(() => {
  return Boolean(
    selectedFile.value &&
    metadata.value &&
    !analyzing.value &&
    !processing.value &&
    !codeError.value &&
    !dateError.value
  );
});

const fileSize = computed(() => {
  if (!selectedFile.value) return "";
  const size = selectedFile.value.size;
  return size < 1024 * 1024
    ? `${(size / 1024).toFixed(1)} KB`
    : `${(size / 1024 / 1024).toFixed(2)} MB`;
});

function validDate(value: string): boolean {
  if (!DATE_PATTERN.test(value)) return false;
  const [year, month, day] = value.split(".").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day;
}

function asApiError(error: unknown): ApiError {
  if (error instanceof ApiFailure) return error.detail;
  return {
    code: "SYSTEM-004",
    category: "SYSTEM",
    stage: "system",
    title: "请求失败",
    message: error instanceof Error ? error.message : "服务暂时不可用。"
  };
}

function handleAuthFailure(error: unknown): boolean {
  if (error instanceof ApiFailure && error.detail.category === "AUTH") {
    authState.value = "anonymous";
    resetTool();
    return true;
  }
  return false;
}

async function checkSession() {
  try {
    await apiFetch("/api/auth/session");
    authState.value = "authenticated";
  } catch {
    authState.value = "anonymous";
  }
}

async function login() {
  if (!password.value || loginBusy.value) return;
  loginBusy.value = true;
  loginError.value = null;
  try {
    await apiFetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: password.value })
    });
    password.value = "";
    authState.value = "authenticated";
  } catch (error) {
    loginError.value = asApiError(error);
  } finally {
    loginBusy.value = false;
  }
}

async function logout() {
  closeEvents();
  try {
    await apiFetch("/api/auth/logout", { method: "POST" });
  } finally {
    authState.value = "anonymous";
    resetTool();
  }
}

function chooseFile() {
  if (!processing.value) fileInput.value?.click();
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = "";
  if (!file) return;
  operationError.value = null;
  errorResult.value = "";
  clearDownload();
  metadata.value = null;
  selectedFile.value = file;
  const extension = file.name.toLowerCase().match(/\.(xlsx|xls)$/)?.[1];
  if (!extension) {
    operationError.value = {
      code: "FILE-001", category: "FILE", stage: "upload",
      title: "不支持的文件格式", message: "仅支持 .xls 和 .xlsx 文件。"
    };
    return;
  }
  if (file.size > MAX_FILE_BYTES) {
    operationError.value = {
      code: "FILE-002", category: "FILE", stage: "upload",
      title: "文件超过大小限制", message: "单个文件不能超过 10 MB。"
    };
    return;
  }
  await analyzeFile(file);
}

async function analyzeFile(file: File) {
  analyzing.value = true;
  progress.value = 5;
  progressStage.value = "upload";
  progressMessage.value = "正在分析文件";
  const form = new FormData();
  form.append("file", file);
  try {
    const response = await apiFetch("/api/analyze", { method: "POST", body: form });
    const result = await response.json() as AnalyzeResponse;
    metadata.value = result.metadata;
    startDate.value = result.metadata.latestPeriod.startDate;
    endDate.value = result.metadata.latestPeriod.endDate;
    progress.value = 0;
    progressStage.value = "ready";
    progressMessage.value = "文件已就绪";
  } catch (error) {
    if (!handleAuthFailure(error)) operationError.value = asApiError(error);
    progress.value = 0;
    progressStage.value = "failed";
    progressMessage.value = "文件分析失败";
  } finally {
    analyzing.value = false;
  }
}

function openCalendar(target: "start" | "end") {
  const input = target === "start" ? startCalendar.value : endCalendar.value;
  if (!input || processing.value) return;
  const picker = input as HTMLInputElement & { showPicker?: () => void };
  if (typeof picker.showPicker === "function") picker.showPicker();
  else input.click();
}

function syncCalendar(target: "start" | "end", event: Event) {
  const value = (event.target as HTMLInputElement).value;
  if (!value) return;
  const formatted = value.replaceAll("-", ".");
  if (target === "start") startDate.value = formatted;
  else endDate.value = formatted;
}

async function startProcess() {
  if (!canProcess.value || !selectedFile.value || !metadata.value) return;
  operationError.value = null;
  errorResult.value = "";
  copied.value = false;
  clearDownload();
  processing.value = true;
  progress.value = 5;
  progressStage.value = "upload";
  progressMessage.value = "正在上传处理副本";

  const latest = metadata.value.latestPeriod;
  const form = new FormData();
  form.append("file", selectedFile.value);
  form.append("sheet_index", String(metadata.value.sheetIndex));
  form.append("title_row", String(latest.titleRow));
  form.append("data_start_row", String(latest.dataStartRow));
  form.append("start_date", startDate.value);
  form.append("end_date", endDate.value);
  form.append("codes", codes.value);
  form.append("fill_name", String(fillName.value));
  form.append("fill_ideal_buy", String(fillIdealBuy.value));
  form.append("fill_ideal_sell", String(fillIdealSell.value));

  try {
    const response = await apiFetch("/api/process", { method: "POST", body: form });
    const accepted = await response.json() as { eventsUrl: string };
    watchTask(accepted.eventsUrl);
  } catch (error) {
    processing.value = false;
    if (!handleAuthFailure(error)) operationError.value = asApiError(error);
    progressStage.value = "failed";
    progressMessage.value = "任务启动失败";
  }
}

function watchTask(eventsUrl: string) {
  closeEvents();
  eventSource = new EventSource(eventsUrl, { withCredentials: true });
  eventSource.addEventListener("progress", (event) => {
    const update = JSON.parse((event as MessageEvent).data) as TaskEvent;
    progress.value = update.progress;
    progressStage.value = update.stage;
    progressMessage.value = update.message;
  });
  eventSource.addEventListener("completed", (event) => {
    const update = JSON.parse((event as MessageEvent).data) as TaskEvent;
    progress.value = 100;
    progressStage.value = "complete";
    progressMessage.value = update.message;
    closeEvents();
    if (update.downloadUrl && update.filename) {
      void fetchDownload(update.downloadUrl, update.filename);
    }
  });
  eventSource.addEventListener("failed", (event) => {
    const update = JSON.parse((event as MessageEvent).data) as TaskEvent;
    progress.value = update.progress;
    progressStage.value = "failed";
    progressMessage.value = update.message;
    operationError.value = update.error ?? asApiError(new Error(update.message));
    errorResult.value = update.result ?? "未生成文件";
    processing.value = false;
    closeEvents();
  });
  eventSource.onerror = () => {
    if (!eventSource) return;
    operationError.value = {
      code: "SYSTEM-004", category: "SYSTEM", stage: "system",
      title: "进度连接中断", message: "无法继续接收任务状态。"
    };
    progressStage.value = "failed";
    processing.value = false;
    closeEvents();
  };
}

async function fetchDownload(url: string, suggestedFilename: string) {
  try {
    const response = await apiFetch(url);
    downloadBlob.value = await response.blob();
    downloadFilename.value = nextFilename(suggestedFilename);
    processing.value = false;
    await nextTick();
    triggerDownload();
  } catch (error) {
    processing.value = false;
    if (!handleAuthFailure(error)) operationError.value = asApiError(error);
    progressStage.value = "failed";
  }
}

function nextFilename(suggested: string): string {
  const match = /^(\d{8})\.(xls|xlsx)$/.exec(suggested);
  if (!match) return suggested;
  const [, day, extension] = match;
  const key = `storpt-download-count:${day}:${extension}`;
  const count = Number.parseInt(localStorage.getItem(key) ?? "0", 10) || 0;
  localStorage.setItem(key, String(count + 1));
  return count === 0 ? suggested : `${day}_${String(count).padStart(2, "0")}.${extension}`;
}

function triggerDownload() {
  if (!downloadBlob.value || !downloadFilename.value) return;
  if (blobUrl) URL.revokeObjectURL(blobUrl);
  blobUrl = URL.createObjectURL(downloadBlob.value);
  const anchor = document.createElement("a");
  anchor.href = blobUrl;
  anchor.download = downloadFilename.value;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

async function copyError() {
  if (!operationError.value) return;
  const error = operationError.value;
  const lines = [
    `[${error.code}] ${error.title}`,
    `阶段：${error.stage}`,
    error.message,
    errorResult.value || "未生成文件"
  ];
  await navigator.clipboard.writeText(lines.join("\n"));
  copied.value = true;
}

function closeEvents() {
  eventSource?.close();
  eventSource = null;
}

function clearDownload() {
  downloadBlob.value = null;
  downloadFilename.value = "";
  if (blobUrl) URL.revokeObjectURL(blobUrl);
  blobUrl = null;
}

function resetTool() {
  closeEvents();
  clearDownload();
  selectedFile.value = null;
  metadata.value = null;
  analyzing.value = false;
  processing.value = false;
  codes.value = "";
  startDate.value = "";
  endDate.value = "";
  fillName.value = false;
  fillIdealBuy.value = false;
  fillIdealSell.value = false;
  progress.value = 0;
  progressStage.value = "idle";
  progressMessage.value = "等待文件";
  operationError.value = null;
  errorResult.value = "";
}

onMounted(checkSession);
onBeforeUnmount(() => {
  closeEvents();
  clearDownload();
});
</script>

<template>
  <div v-if="authState === 'loading'" class="loading-screen" aria-live="polite">
    <img src="/app-mark.svg" alt="" class="brand-mark large" />
    <LoaderCircle class="spin" :size="24" />
  </div>

  <main v-else-if="authState === 'anonymous'" class="login-layout">
    <section class="login-panel" aria-labelledby="login-title">
      <header class="brand-lockup">
        <img src="/app-mark.svg" alt="" class="brand-mark" />
        <div>
          <h1 id="login-title">StoRpt</h1>
          <p>A 股历史价格回填</p>
        </div>
      </header>
      <form class="login-form" @submit.prevent="login">
        <label for="password">访问密码</label>
        <input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          :disabled="loginBusy"
          autofocus
        />
        <button class="primary-button" type="submit" :disabled="!password || loginBusy">
          <LoaderCircle v-if="loginBusy" class="spin" :size="18" />
          <span>登录</span>
        </button>
      </form>
      <div v-if="loginError" class="inline-error" role="alert">
        <AlertTriangle :size="18" />
        <span>{{ loginError.message }}</span>
      </div>
    </section>
  </main>

  <main v-else class="app-layout">
    <section class="tool-surface" aria-labelledby="app-title">
      <header class="app-header">
        <div class="brand-lockup compact">
          <img src="/app-mark.svg" alt="" class="brand-mark" />
          <div>
            <h1 id="app-title">StoRpt</h1>
            <p>A 股历史价格回填</p>
          </div>
        </div>
        <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout">
          <LogOut :size="19" />
        </button>
      </header>

      <form class="tool-form" @submit.prevent="startProcess">
        <fieldset :disabled="processing" class="form-fields">
          <div class="field-group">
            <div class="label-row">
              <label>Excel 文件</label>
              <span class="constraint">XLS / XLSX · 10 MB</span>
            </div>
            <input ref="fileInput" class="visually-hidden" type="file" accept=".xls,.xlsx" @change="onFileChange" />
            <button class="upload-control" type="button" @click="chooseFile">
              <UploadCloud v-if="!analyzing" :size="22" />
              <LoaderCircle v-else class="spin" :size="22" />
              <span>{{ selectedFile ? '重新选择文件' : '选择文件' }}</span>
            </button>
            <div v-if="selectedFile" class="file-summary">
              <FileSpreadsheet :size="20" />
              <div class="file-main">
                <strong>{{ selectedFile.name }}</strong>
                <span>{{ fileSize }}</span>
              </div>
              <CheckCircle2 v-if="metadata" class="success-icon" :size="20" />
            </div>
            <dl v-if="metadata" class="metadata-strip">
              <div><dt>目标表</dt><dd>{{ metadata.sheetName }}</dd></div>
              <div><dt>最新时间段</dt><dd>{{ metadata.latestPeriod.startDate }} - {{ metadata.latestPeriod.endDate }}</dd></div>
            </dl>
          </div>

          <div class="field-group">
            <div class="label-row">
              <label for="codes">股票代码</label>
              <span class="count-badge" :class="{ invalid: codeError && codes.trim() }">{{ parsedCodes.length }} 只</span>
            </div>
            <textarea
              id="codes"
              v-model="codes"
              rows="4"
              spellcheck="false"
              autocomplete="off"
              inputmode="numeric"
              placeholder="000001, 600000"
            />
            <p v-if="codes.trim() && codeError" class="field-error">{{ codeError }}</p>
          </div>

          <div class="date-grid">
            <div class="field-group">
              <label for="start-date">开始日期</label>
              <div class="date-control">
                <input id="start-date" v-model.trim="startDate" inputmode="numeric" placeholder="yyyy.MM.dd" maxlength="10" />
                <button type="button" class="date-button" title="选择开始日期" aria-label="选择开始日期" @click="openCalendar('start')">
                  <CalendarDays :size="19" />
                </button>
                <input ref="startCalendar" class="native-calendar" type="date" @change="syncCalendar('start', $event)" />
              </div>
            </div>
            <div class="field-group">
              <label for="end-date">结束日期</label>
              <div class="date-control">
                <input id="end-date" v-model.trim="endDate" inputmode="numeric" placeholder="yyyy.MM.dd" maxlength="10" />
                <button type="button" class="date-button" title="选择结束日期" aria-label="选择结束日期" @click="openCalendar('end')">
                  <CalendarDays :size="19" />
                </button>
                <input ref="endCalendar" class="native-calendar" type="date" @change="syncCalendar('end', $event)" />
              </div>
            </div>
          </div>
          <p v-if="metadata && dateError" class="field-error date-message">{{ dateError }}</p>

          <div class="field-group">
            <span class="group-label">写入字段</span>
            <div class="checkbox-list">
              <label><input v-model="fillName" type="checkbox" /> <span>股票名称</span></label>
              <label><input v-model="fillIdealBuy" type="checkbox" /> <span>理想进价</span></label>
              <label><input v-model="fillIdealSell" type="checkbox" /> <span>理想出价</span></label>
            </div>
          </div>
        </fieldset>

        <button class="primary-button process-button" type="submit" :disabled="!canProcess">
          <LoaderCircle v-if="processing" class="spin" :size="19" />
          <Play v-else :size="19" fill="currentColor" />
          <span>{{ processing ? '处理中' : '开始处理' }}</span>
        </button>
      </form>

      <section class="status-section" aria-live="polite" aria-atomic="true">
        <div class="status-heading">
          <span>任务状态</span>
          <strong>{{ progress }}%</strong>
        </div>
        <div class="progress-track" :class="{ failed: progressStage === 'failed', complete: progressStage === 'complete' }">
          <div class="progress-value" :style="{ width: `${progress}%` }"></div>
        </div>
        <p class="status-message">{{ progressMessage }}</p>
      </section>

      <section v-if="operationError" class="error-panel" role="alert">
        <div class="error-title-row">
          <div>
            <span class="error-code">{{ operationError.code }}</span>
            <h2>{{ operationError.title }}</h2>
          </div>
          <button class="icon-button" type="button" :title="copied ? '已复制' : '复制错误'" :aria-label="copied ? '已复制' : '复制错误'" @click="copyError">
            <CheckCircle2 v-if="copied" :size="18" />
            <Clipboard v-else :size="18" />
          </button>
        </div>
        <p>{{ operationError.message }}</p>
        <strong v-if="errorResult">{{ errorResult }}</strong>
      </section>

      <section v-if="downloadBlob" class="download-panel">
        <div>
          <span>输出文件</span>
          <strong>{{ downloadFilename }}</strong>
        </div>
        <button class="secondary-button" type="button" @click="triggerDownload">
          <Download :size="18" />
          <span>下载文件</span>
        </button>
      </section>

      <button v-if="operationError && !processing" class="text-button" type="button" @click="operationError = null; errorResult = ''">
        <RefreshCw :size="16" />
        <span>返回编辑</span>
      </button>
    </section>
  </main>
</template>
