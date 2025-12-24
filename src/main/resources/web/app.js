const cards = document.getElementById("cards");
const statClients = document.getElementById("statClients");
const statCpu = document.getElementById("statCpu");
const statUpdated = document.getElementById("statUpdated");
const connectionState = document.getElementById("connectionState");
const toastStack = document.getElementById("toastStack");
const screenModal = document.getElementById("screenModal");
const screenTitle = document.getElementById("screenTitle");
const screenStatus = document.getElementById("screenStatus");
const screenImage = document.getElementById("screenImage");
const cardByClient = new Map();
const statusByClient = new Map();

const POLL_MS = 2000;
const TOAST_MS = 5000;
const SCREENSHOT_ATTEMPTS = 8;

let activeScreenClient = null;
let screenObjectUrl = null;
let screenPollTimer = null;

function formatPercent(value) {
  if (value == null || Number.isNaN(value)) {
    return "--";
  }
  return `${(value * 100).toFixed(1)}%`;
}

function formatTime(ts) {
  if (!ts) {
    return "--";
  }
  const date = new Date(ts);
  return date.toLocaleTimeString();
}

function createCard(clientId) {
  const card = document.createElement("article");
  card.className = "card";
  card.dataset.clientId = clientId;
  card.innerHTML = `
    <div class="card__head">
      <h2 class="card__title" data-field="title"></h2>
      <span class="badge badge--online" data-field="statusBadge">Online</span>
    </div>
    <div class="actions">
      <button class="action-btn" type="button" data-field="requestBtn">
        Request monitoring
      </button>
      <button class="action-btn action-btn--ghost" type="button" data-field="screenBtn">
        View screen
      </button>
      <span class="action-state" data-field="approvalState"></span>
    </div>
    <div class="kv">
      <span>CPU load</span>
      <strong data-field="cpuText"></strong>
    </div>
    <div class="meter"><div class="meter__fill" data-field="cpuMeter"></div></div>
    <div class="kv">
      <span>Memory</span>
      <strong data-field="ramText"></strong>
    </div>
    <div class="meter"><div class="meter__fill" data-field="ramMeter"></div></div>
    <div class="kv">
      <span>Processes</span>
      <strong data-field="procText"></strong>
    </div>
    <div class="kv">
      <span>Last seen</span>
      <strong data-field="seenText"></strong>
    </div>
  `;
  return card;
}

function updateCard(card, client) {
  const title = card.querySelector('[data-field="title"]');
  const cpuText = card.querySelector('[data-field="cpuText"]');
  const cpuMeter = card.querySelector('[data-field="cpuMeter"]');
  const ramText = card.querySelector('[data-field="ramText"]');
  const ramMeter = card.querySelector('[data-field="ramMeter"]');
  const procText = card.querySelector('[data-field="procText"]');
  const seenText = card.querySelector('[data-field="seenText"]');
  const statusBadge = card.querySelector('[data-field="statusBadge"]');
  const requestBtn = card.querySelector('[data-field="requestBtn"]');
  const screenBtn = card.querySelector('[data-field="screenBtn"]');
  const approvalState = card.querySelector('[data-field="approvalState"]');

  const ramUsed = client.ramUsedMb ?? 0;
  const ramTotal = client.ramTotalMb ?? 0;
  const ramPercent = ramTotal > 0 ? (ramUsed / ramTotal) * 100 : 0;
  const cpuPercent = Math.min(100, Math.max(4, (client.cpuLoad || 0) * 100));
  const isOnline = client.online !== false;
  const approved = client.monitoringAllowed === true;
  const pending = client.pendingCommand === "REQUEST_MONITORING";

  title.textContent = client.clientId || "unknown";
  cpuText.textContent = formatPercent(client.cpuLoad);
  cpuMeter.style.width = `${cpuPercent}%`;
  ramText.textContent = `${ramUsed} / ${ramTotal} MB`;
  ramMeter.style.width = `${Math.min(100, Math.max(4, ramPercent))}%`;
  procText.textContent = client.processCount ?? 0;
  seenText.textContent = formatTime(client.lastSeen);
  statusBadge.textContent = isOnline ? "Online" : "Offline";
  statusBadge.className = `badge ${isOnline ? "badge--online" : "badge--offline"}`;
  card.classList.toggle("card--offline", !isOnline);

  if (approved) {
    approvalState.textContent = "Monitoring allowed";
  } else if (pending) {
    approvalState.textContent = "Waiting for approval";
  } else {
    approvalState.textContent = "Monitoring not allowed";
  }

  requestBtn.disabled = !isOnline || approved || pending;
  requestBtn.textContent = pending ? "Request sent" : "Request monitoring";
  requestBtn.dataset.clientId = client.clientId || "";

  screenBtn.disabled = !isOnline || !approved;
  screenBtn.dataset.clientId = client.clientId || "";
}

function showToast(message, type) {
  if (!toastStack) {
    return;
  }
  const toast = document.createElement("div");
  toast.className = `toast toast--${type}`;
  toast.textContent = message;
  toastStack.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add("toast--show"));
  setTimeout(() => {
    toast.classList.remove("toast--show");
    setTimeout(() => toast.remove(), 200);
  }, TOAST_MS);
}

function clearScreenImage() {
  if (screenObjectUrl) {
    URL.revokeObjectURL(screenObjectUrl);
    screenObjectUrl = null;
  }
}

function openScreenModal(clientId) {
  if (!screenModal) {
    return;
  }
  activeScreenClient = clientId;
  if (screenTitle) {
    screenTitle.textContent = `Screen: ${clientId}`;
  }
  if (screenStatus) {
    screenStatus.textContent = "Requesting screenshot...";
  }
  if (screenImage) {
    screenImage.src = "";
  }
  screenModal.classList.add("is-open");
  screenModal.setAttribute("aria-hidden", "false");
}

function closeScreenModal() {
  if (!screenModal) {
    return;
  }
  screenModal.classList.remove("is-open");
  screenModal.setAttribute("aria-hidden", "true");
  if (screenPollTimer) {
    clearTimeout(screenPollTimer);
    screenPollTimer = null;
  }
  clearScreenImage();
  activeScreenClient = null;
}

async function requestScreenshot(clientId) {
  if (!clientId) {
    return false;
  }
  try {
    const response = await fetch(
      `/api/command?clientId=${encodeURIComponent(clientId)}&action=request_screenshot`,
      { method: "POST" },
    );
    if (!response.ok) {
      throw new Error("Request failed");
    }
    showToast(`${clientId}: screenshot requested`, "online");
    return true;
  } catch (err) {
    showToast(`${clientId}: screenshot request failed`, "offline");
    return false;
  }
}

async function fetchScreenshot(clientId) {
  const response = await fetch(
    `/api/screenshot?clientId=${encodeURIComponent(clientId)}&ts=${Date.now()}`,
  );
  if (!response.ok) {
    return null;
  }
  return response.blob();
}

function waitForScreenshot(clientId, attempts = SCREENSHOT_ATTEMPTS) {
  if (!clientId) {
    return;
  }
  let tries = 0;
  const tryFetch = async () => {
    tries += 1;
    const blob = await fetchScreenshot(clientId);
    if (blob) {
      clearScreenImage();
      screenObjectUrl = URL.createObjectURL(blob);
      if (screenImage) {
        screenImage.src = screenObjectUrl;
      }
      if (screenStatus) {
        screenStatus.textContent = `Captured at ${formatTime(Date.now())}`;
      }
      return;
    }
    if (tries >= attempts) {
      if (screenStatus) {
        screenStatus.textContent = "Screenshot not ready yet.";
      }
      return;
    }
    if (screenStatus) {
      screenStatus.textContent = "Waiting for screenshot...";
    }
    screenPollTimer = setTimeout(tryFetch, 1000);
  };
  tryFetch();
}

function render(data) {
  const clients = data.clients || [];
  statClients.textContent = clients.length;
  const avgCpu =
    clients.length === 0
      ? null
      : clients.reduce((sum, client) => sum + (client.cpuLoad || 0), 0) /
        clients.length;
  statCpu.textContent = formatPercent(avgCpu);
  statUpdated.textContent = formatTime(data.serverTime);

  const ordered = clients.sort(
    (a, b) =>
      Number(b.online !== false) - Number(a.online !== false) ||
      (b.lastSeen || 0) - (a.lastSeen || 0),
  );

  const seen = new Set();
  ordered.forEach((client, index) => {
    const id = client.clientId || "unknown";
    seen.add(id);
    let card = cardByClient.get(id);
    if (!card) {
      card = createCard(id);
      card.style.animationDelay = `${index * 60}ms`;
      cardByClient.set(id, card);
      cards.appendChild(card);
    } else if (card.parentElement !== cards) {
      cards.appendChild(card);
    }
    updateCard(card, client);
    const prev = statusByClient.get(id);
    const isOnline = client.online !== false;
    const approved = client.monitoringAllowed === true;
    if (!prev) {
      if (isOnline) {
        showToast(`${id} connected`, "online");
      }
    } else if (prev.online !== isOnline) {
      showToast(
        `${id} ${isOnline ? "connected" : "disconnected"}`,
        isOnline ? "online" : "offline",
      );
    }
    if (prev && prev.monitoringAllowed !== approved) {
      showToast(
        `${id} ${approved ? "approved monitoring" : "denied monitoring"}`,
        approved ? "online" : "offline",
      );
    }
    if (activeScreenClient === id && !approved) {
      closeScreenModal();
    }
    statusByClient.set(id, { online: isOnline, monitoringAllowed: approved });
  });

  Array.from(cardByClient.entries()).forEach(([id, card]) => {
    if (!seen.has(id)) {
      card.remove();
      cardByClient.delete(id);
      statusByClient.delete(id);
    }
  });
}

async function requestMonitoring(clientId) {
  if (!clientId) {
    return;
  }
  try {
    const response = await fetch(
      `/api/command?clientId=${encodeURIComponent(clientId)}&action=request_monitoring`,
      { method: "POST" },
    );
    if (!response.ok) {
      throw new Error("Request failed");
    }
    showToast(`${clientId}: request sent`, "online");
  } catch (err) {
    showToast(`${clientId}: request failed`, "offline");
  }
}

cards.addEventListener("click", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLButtonElement)) {
    return;
  }
  if (target.dataset.field === "requestBtn") {
    requestMonitoring(target.dataset.clientId);
    return;
  }
  if (target.dataset.field === "screenBtn") {
    const clientId = target.dataset.clientId;
    openScreenModal(clientId);
    requestScreenshot(clientId).then((ok) => {
      if (ok) {
        waitForScreenshot(clientId);
      } else if (screenStatus) {
        screenStatus.textContent = "Screenshot request failed.";
      }
    });
  }
});

if (screenModal) {
  screenModal.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) {
      return;
    }
    const action = target.dataset.action;
    if (action === "close") {
      closeScreenModal();
    } else if (action === "refresh") {
      if (!activeScreenClient) {
        return;
      }
      if (screenStatus) {
        screenStatus.textContent = "Requesting screenshot...";
      }
      requestScreenshot(activeScreenClient).then((ok) => {
        if (ok) {
          waitForScreenshot(activeScreenClient);
        } else if (screenStatus) {
          screenStatus.textContent = "Screenshot request failed.";
        }
      });
    }
  });
}

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && screenModal && screenModal.classList.contains("is-open")) {
    closeScreenModal();
  }
});

async function poll() {
  try {
    const response = await fetch("/api/status");
    if (!response.ok) {
      throw new Error("Bad response");
    }
    const data = await response.json();
    render(data);
    connectionState.textContent = "Connected";
    connectionState.style.color = "#22c55e";
  } catch (err) {
    connectionState.textContent = "Disconnected";
    connectionState.style.color = "#f97316";
  }
}

poll();
setInterval(poll, POLL_MS);
