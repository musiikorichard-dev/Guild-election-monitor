const numberFormat = new Intl.NumberFormat("en-UG");
const OTHER_UNIT_VALUE = "__OTHER_UNIT__";
const OTHER_SCHOOL_VALUE = "__OTHER_SCHOOL__";
const COLLEGES = [
  { value: "College of Agricultural and Environmental Sciences", acronym: "CAES" },
  { value: "College of Business and Management Sciences", acronym: "CoBAMS" },
  { value: "College of Computing and Information Sciences", acronym: "CoCIS" },
  { value: "College of Education and External Studies", acronym: "CEES" },
  { value: "College of Engineering, Design, Art and Technology", acronym: "CEDAT" },
  { value: "College of Health Sciences", acronym: "CHS" },
  { value: "College of Humanities and Social Sciences", acronym: "CHUSS" },
  { value: "College of Natural Sciences", acronym: "CoNAS" },
  { value: "College of Veterinary Medicine, Animal Resource and Bio-Security", acronym: "CoVAB" },
  { value: "School of Law", acronym: "" }
];
const STANDALONE_SCHOOLS = [
  { value: "School of Public Health", acronym: "MakSPH" },
  { value: "Institute of Gender and Development Studies", acronym: "IGDS" }
];
const POST_ACRONYMS = {
  CGC_CHAIRPERSON: { acronym: "CGC", fullForm: "College Guild Council", separator: " " },
  GRC: { acronym: "GRC", fullForm: "Guild Representative Council" },
  CGC: { acronym: "CGC", fullForm: "College Guild Council" },
  GRC_HALL: { acronym: "GRC", fullForm: "Guild Representative Council" },
  GRC_EXTERNAL_CAMPUS: { acronym: "GRC", fullForm: "Guild Representative Council" }
};
const OBSERVATION_CATEGORIES = {
  TALLY_DISCREPANCY: "Tally discrepancy",
  ACCESS_QUEUE: "Access or queue issue",
  EQUIPMENT_CONNECTIVITY: "Equipment or connectivity",
  STAFFING_PROCESS: "Staffing or process",
  VOTER_ASSISTANCE: "Voter assistance needed",
  OTHER: "Other operational issue"
};
let publicState = null;
let privateState = null;
let sessionUser = null;
let activeAgentTab = "report";
let activePage = "overview";
let hadWorkspace = false;
let toastTimer;

const byId = (id) => document.getElementById(id);
const filters = {
  race: byId("active-race"),
  college: byId("active-college"),
  school: byId("active-school")
};

function pageFromUrl() {
  const requested = window.location.hash.slice(1);
  return [...document.querySelectorAll("[data-app-page]")]
    .some((section) => section.dataset.appPage === requested)
    ? requested
    : "overview";
}

function updatePageUrl(name, replace = false) {
  const url = new URL(window.location.href);
  url.hash = name === "overview" ? "" : name;
  const method = replace ? "replaceState" : "pushState";
  window.history[method]({ appPage: name }, "", url);
}

function showAppPage(name, resetScroll = true, syncUrl = true) {
  const page = byId(`page-${name}`);
  const operationsLink = document.querySelector('[data-page-target="operations"]');
  if (!page || (name === "operations" && operationsLink.hidden)) return;
  if (syncUrl && pageFromUrl() !== name) updatePageUrl(name);
  const restoreFocus = byId("app-sidebar").classList.contains("is-open");
  activePage = name;
  for (const section of document.querySelectorAll("[data-app-page]")) {
    section.hidden = section.dataset.appPage !== name;
  }
  for (const button of document.querySelectorAll("[data-page-target]")) {
    const selected = button.dataset.pageTarget === name;
    button.classList.toggle("is-active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
  setSidebarOpen(!window.matchMedia("(max-width: 900px)").matches, restoreFocus);
  if (resetScroll) window.scrollTo(0, 0);
}

function setSidebarOpen(open, restoreFocus = false) {
  const mobile = window.matchMedia("(max-width: 900px)").matches;
  const expanded = Boolean(open);
  const sidebar = byId("app-sidebar");
  const toggle = byId("sidebar-toggle");
  const drawerOpen = mobile && expanded;
  if (drawerOpen && toggle.getAttribute("aria-expanded") !== "true") {
    document.documentElement.classList.add("sidebar-open");
  } else if (!drawerOpen) {
    document.documentElement.classList.remove("sidebar-open");
  }
  sidebar.classList.toggle("is-open", drawerOpen);
  document.querySelector("main.app-layout").classList.toggle("sidebar-collapsed", !mobile && !expanded);
  sidebar.inert = !expanded;
  sidebar.setAttribute("aria-hidden", String(!expanded));
  document.querySelector(".page-content").inert = drawerOpen;
  byId("sidebar-backdrop").hidden = !drawerOpen;
  toggle.setAttribute("aria-expanded", String(expanded));
  toggle.setAttribute("aria-label", expanded ? "Close navigation" : "Open navigation");
  byId("menu-toggle-label").textContent = expanded ? "Close" : "Menu";
  if (drawerOpen) byId("sidebar-close").focus();
  if (restoreFocus) byId("sidebar-toggle").focus();
}

document.querySelector(".sidebar-nav").addEventListener("click", (event) => {
  const button = event.target.closest("[data-page-target]");
  if (button && !button.hidden) showAppPage(button.dataset.pageTarget);
});
byId("sidebar-toggle").addEventListener("click", () => {
  setSidebarOpen(byId("sidebar-toggle").getAttribute("aria-expanded") !== "true");
});
byId("sidebar-backdrop").addEventListener("click", () => setSidebarOpen(false, true));
byId("sidebar-close").addEventListener("click", () => setSidebarOpen(false, true));
document.addEventListener("keydown", (event) => {
  const drawerOpen = window.matchMedia("(max-width: 900px)").matches
    && byId("sidebar-toggle").getAttribute("aria-expanded") === "true";
  if (event.key === "Escape" && drawerOpen) {
    setSidebarOpen(false, true);
    return;
  }
  if (event.key !== "Tab" || !drawerOpen) return;
  const focusable = [...byId("app-sidebar").querySelectorAll('button:not([hidden]):not(:disabled), a[href], [tabindex="0"]')];
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (!byId("app-sidebar").contains(document.activeElement)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
});
let wasMobileNavigation = window.matchMedia("(max-width: 900px)").matches;
window.addEventListener("resize", () => {
  const mobileNavigation = window.matchMedia("(max-width: 900px)").matches;
  if (mobileNavigation !== wasMobileNavigation) {
    setSidebarOpen(!mobileNavigation);
    wasMobileNavigation = mobileNavigation;
  }
});
window.addEventListener("popstate", () => {
  const name = pageFromUrl();
  const operationsLink = document.querySelector('[data-page-target="operations"]');
  if (name === "operations" && operationsLink.hidden) {
    showAppPage("overview", false, false);
    updatePageUrl("overview", true);
    return;
  }
  showAppPage(name, false, false);
});
setSidebarOpen(!window.matchMedia("(max-width: 900px)").matches);
const initialPage = pageFromUrl();
if (initialPage !== "operations") showAppPage(initialPage, false, false);

function showToast(message, isError = false) {
  const toast = byId("toast");
  toast.textContent = message;
  toast.classList.toggle("error", isError);
  toast.classList.add("visible");
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 3500);
}

function applyTheme(theme) {
  const lightMode = theme === "light";
  const button = byId("theme-toggle");
  byId("theme-stylesheet").disabled = lightMode;
  button.setAttribute("aria-pressed", String(lightMode));
  const nextMode = lightMode ? "dark" : "light";
  const label = `Switch to ${nextMode} mode`;
  button.setAttribute("aria-label", label);
  button.title = label;
  button.querySelector(".theme-toggle-label").textContent = `${nextMode} mode`;
  button.querySelector(".theme-toggle-icon").textContent = lightMode ? "\u263E" : "\u263C";
  byId("theme-color").content = lightMode ? "#f4f6f7" : "#10171f";
}

function initializeTheme() {
  let theme = "dark";
  let storageError = null;
  try {
    theme = localStorage.getItem("makerere-election-theme") === "light" ? "light" : "dark";
  } catch (error) {
    storageError = error;
    console.error("Could not load the saved theme preference.", error);
  }
  applyTheme(theme);
  byId("theme-toggle").addEventListener("click", () => {
    const nextTheme = byId("theme-toggle").getAttribute("aria-pressed") === "true" ? "dark" : "light";
    applyTheme(nextTheme);
    try {
      localStorage.setItem("makerere-election-theme", nextTheme);
    } catch (error) {
      console.error("Could not save the theme preference.", error);
      showToast("Theme changed, but your preference could not be saved.", true);
    }
  });
  if (storageError) showToast("Could not load your saved theme preference; using dark mode.", true);
}

initializeTheme();

function fmt(value) {
  return numberFormat.format(value || 0);
}

function dateLabel(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Unknown time"
    : date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

function unitOption(name, directory) {
  const known = directory.find((unit) => unit.value === name);
  if (!known || !known.acronym) return { value: name, label: name };
  return {
    value: name,
    label: `${known.acronym} (${name})`
  };
}

function shortUnitName(name, directory) {
  const known = directory.find((unit) => unit.value === name);
  return known && known.acronym ? known.acronym : name;
}

function centerUnitLabel(center) {
  if (center.college) return shortUnitName(center.college, COLLEGES);
  return shortUnitName(center.school, STANDALONE_SCHOOLS) || "Not specified";
}

function fullCenterUnit(center) {
  return center.college || center.school || "Not specified";
}

function postLabel(post) {
  if (post.isTemplate) return post.name;
  const official = POST_ACRONYMS[post.category];
  if (!official) return post.name;
  const { acronym, fullForm, separator = " " } = official;
  let name = post.name.replace(new RegExp(`\\s*\\(${acronym}\\)\\s*$`, "i"), "").trim();
  for (const prefix of [acronym, fullForm]) {
    if (name.toLowerCase() === prefix.toLowerCase()) {
      name = "";
      break;
    }
    if (name.toLowerCase().startsWith(`${prefix.toLowerCase()} `)) {
      name = name.slice(prefix.length).replace(/^[-–—:]\s*/, "").trim();
    }
  }
  const suffix = name ? `${separator}${name}` : "";
  return `${acronym}${suffix} (${fullForm}${suffix})`;
}

function selectedRaceId() {
  return filters.race.value ? Number(filters.race.value) : null;
}

function officialPostTemplates() {
  const select = document.querySelector('#race-form select[name="category"]');
  if (!select) return [];
  return [...select.options]
    .filter((option) => option.value && option.value !== "OTHER")
    .map((option, index) => ({
      id: -(index + 1),
      category: option.value,
      name: option.textContent,
      isTemplate: true
    }));
}

function selectedPost() {
  if (!publicState) return null;
  const selectedId = selectedRaceId();
  return publicState.races.find((post) => post.id === selectedId)
    || officialPostTemplates().find((post) => post.id === selectedId)
    || null;
}

function selectedCollege() {
  return filters.college.value;
}

function selectedSchool() {
  return filters.school.value;
}

function centersInView() {
  const post = selectedPost();
  if (!publicState || (post && post.isTemplate)) return [];
  return publicState.stations.filter((center) =>
    (!selectedCollege() || center.college === selectedCollege())
    && (!selectedSchool() || center.school === selectedSchool())
  );
}

function latestReportsForView() {
  const post = selectedPost();
  if (!publicState || !post || post.isTemplate) return [];
  const centerIds = new Set(centersInView().map((center) => center.id));
  const latest = new Map();
  for (const report of publicState.reports) {
    if (report.raceId !== post.id || !centerIds.has(report.centerId)) continue;
    const key = String(report.centerId);
    if (!latest.has(key) || report.id > latest.get(key).id) latest.set(key, report);
  }
  return [...latest.values()];
}

function setOptions(select, placeholder, items, selectedValue) {
  const previous = selectedValue ?? select.value;
  select.replaceChildren();
  const prompt = document.createElement("option");
  prompt.value = "";
  prompt.textContent = placeholder;
  select.append(prompt);
  for (const item of items) {
    const option = document.createElement("option");
    option.value = String(item.value);
    option.textContent = item.label;
    select.append(option);
  }
  if (items.some((item) => String(item.value) === String(previous))) {
    select.value = String(previous);
  }
}

function refreshFilters() {
  if (!publicState) return;
  const postSelection = filters.race.value;
  const posts = publicState.races.length ? publicState.races : officialPostTemplates();
  const selectedPost = posts.some((post) => String(post.id) === postSelection)
    ? postSelection
    : posts[0] && posts[0].id;
  setOptions(filters.race, "Select a post", posts.map((post) => ({
    value: post.id,
    label: post.isTemplate ? `${postLabel(post)} · template` : postLabel(post)
  })), selectedPost);
  byId("post-selector-note").textContent = publicState.races.length
    ? "Choose an active post to follow its interim results."
    : "Official 92nd Guild post templates are shown for reference only; confirm the current ballot with the Electoral Commission.";

  const colleges = [...new Set([
    ...COLLEGES.map((college) => college.value),
    ...publicState.stations.map((center) => center.college).filter(Boolean)
  ])].sort((a, b) => a.localeCompare(b));
  setOptions(filters.college, "All colleges", colleges.map((name) => unitOption(name, COLLEGES)));
  const schools = [...new Set([
    ...STANDALONE_SCHOOLS.map((school) => school.value),
    ...publicState.stations
      .filter((center) => !center.college)
      .map((center) => center.school).filter(Boolean)
  ])].sort((a, b) => a.localeCompare(b));
  setOptions(filters.school, "All schools", schools.map((name) => unitOption(name, STANDALONE_SCHOOLS)));
  filters.school.disabled = Boolean(filters.college.value);
}

function renderCenterDirectory() {
  const collegeSelect = byId("center-college");
  if (!collegeSelect || !privateState) return;
  const extraColleges = privateState.stations
    .map((center) => center.college)
    .filter((name) => name && !COLLEGES.some((college) => college.value === name));
  const collegeOptions = [...new Set([...COLLEGES.map((college) => college.value), ...extraColleges])]
    .sort((a, b) => a.localeCompare(b))
    .map((name) => unitOption(name, COLLEGES));
  collegeOptions.push({ value: OTHER_UNIT_VALUE, label: "Other official college / unit…" });
  setOptions(collegeSelect, "Select a college or unit", collegeOptions);

  const selectedCollege = collegeSelect.value;
  const otherCollegeInput = byId("other-college-wrap").querySelector("input");
  byId("other-college-wrap").hidden = selectedCollege !== OTHER_UNIT_VALUE;
  otherCollegeInput.required = selectedCollege === OTHER_UNIT_VALUE;

  const extraSchools = privateState.stations
    .filter((center) => !center.college)
    .map((center) => center.school)
    .filter((name) => name && !STANDALONE_SCHOOLS.some((school) => school.value === name));
  const schoolOptions = [...new Set([...STANDALONE_SCHOOLS.map((school) => school.value), ...extraSchools])]
    .sort((a, b) => a.localeCompare(b))
    .map((name) => unitOption(name, STANDALONE_SCHOOLS));
  schoolOptions.unshift({ value: "", label: "No college-free school selected" });
  schoolOptions.push({ value: OTHER_SCHOOL_VALUE, label: "Other official school / institute…" });
  const schoolSelect = byId("center-school");
  setOptions(schoolSelect, "Select a stand-alone school or unit", schoolOptions);
  schoolSelect.disabled = Boolean(selectedCollege);
  const otherSchoolInput = byId("other-school-wrap").querySelector("input");
  const isOtherSchool = !selectedCollege && schoolSelect.value === OTHER_SCHOOL_VALUE;
  byId("other-school-wrap").hidden = !isOtherSchool;
  otherSchoolInput.required = isOtherSchool;
}

function render() {
  if (!publicState) return;
  refreshFilters();
  byId("election-title").textContent = publicState.title;
  document.title = `Live Election Updates | ${publicState.title}`;
  renderResults();
  renderCentres();
  renderUpdates();
  renderWorkspace();
}

function renderResults() {
  const post = selectedPost();
  const activePostId = post && !post.isTemplate ? post.id : null;
  const centers = centersInView();
  const reports = latestReportsForView();
  const totals = new Map(publicState.candidates
    .filter((candidate) => candidate.raceId === activePostId)
    .map((candidate) => [candidate.id, 0]));
  let totalVotes = 0;
  for (const report of reports) {
    for (const [candidateId, votes] of Object.entries(report.votes)) {
      if (!totals.has(Number(candidateId))) continue;
      totals.set(Number(candidateId), totals.get(Number(candidateId)) + votes);
      totalVotes += votes;
    }
  }
  byId("total-votes").textContent = fmt(totalVotes);
  byId("vote-scope").textContent = post
    ? `${postLabel(post)}${post.isTemplate ? " · template" : selectedSchool() ? ` · ${selectedSchool()}` : selectedCollege() ? ` · ${selectedCollege()}` : ""}`
    : "No posts configured";
  byId("centres-reporting").textContent = post && post.isTemplate ? "Not active" : `${reports.length} / ${centers.length}`;
  byId("coverage-caption").textContent = post && post.isTemplate
    ? "Template not configured for this election"
    : centers.length
    ? `${Math.max(0, centers.length - reports.length)} centre${centers.length - reports.length === 1 ? "" : "s"} awaiting a report`
    : "Tally centres will appear when configured";
  const latest = [...reports].sort((a, b) => b.id - a.id)[0];
  byId("last-update").textContent = latest ? dateLabel(latest.recordedAt) : "No reports yet";
  byId("update-caption").textContent = latest ? "Latest declaration received" : "Updates appear automatically";
  byId("results-context").textContent = post
    ? post.isTemplate
      ? "This template is not an active post for the current election. No results are available until the election team configures it."
      : `Latest report per tally centre for ${postLabel(post)} is included; earlier submissions remain in the activity log.`
    : "The election team has not configured any posts yet.";

  const container = byId("candidate-results");
  container.replaceChildren();
  container.setAttribute(
    "aria-label",
    post ? `Candidate vote share for ${postLabel(post)}` : "Candidate vote comparison"
  );
  const candidates = publicState.candidates.filter((candidate) => candidate.raceId === activePostId)
    .sort((a, b) => (totals.get(b.id) || 0) - (totals.get(a.id) || 0));
  if (!post) {
    container.append(emptyState("Results will appear once the election team configures official posts."));
    return;
  }
  if (post.isTemplate) {
    container.append(emptyState("This official template is not configured for the current election."));
    return;
  }
  if (!candidates.length) {
    container.append(emptyState("Candidates have not been added from the current official list."));
    return;
  }
  candidates.sort((a, b) =>
    (totals.get(b.id) || 0) - (totals.get(a.id) || 0)
    || a.name.localeCompare(b.name)
  );
  const leadingVotes = totals.get(candidates[0].id) || 0;
  const tiedForLead = leadingVotes > 0
    && candidates.filter((candidate) => (totals.get(candidate.id) || 0) === leadingVotes).length > 1;
  for (const candidate of candidates) {
    const votes = totals.get(candidate.id) || 0;
    const percent = totalVotes ? votes * 100 / totalVotes : 0;
    const isLeading = candidates.length > 1 && leadingVotes > 0 && votes === leadingVotes;
    const row = document.createElement("article");
    row.className = `candidate-row${isLeading ? " is-leading" : ""}`;
    const identity = document.createElement("div");
    identity.className = "candidate-identity";
    const name = document.createElement("strong");
    name.className = "candidate-name";
    name.textContent = candidate.name;
    identity.append(name);
    if (isLeading) {
      const status = document.createElement("span");
      status.className = "candidate-status";
      status.textContent = tiedForLead ? "Tied for lead" : "Leading";
      identity.append(status);
    }

    const metrics = document.createElement("div");
    metrics.className = "candidate-metrics";
    const total = document.createElement("div");
    total.className = "candidate-total";
    const count = document.createElement("strong");
    count.className = "candidate-votes";
    count.textContent = fmt(votes);
    const voteLabel = document.createElement("span");
    voteLabel.className = "candidate-vote-unit";
    voteLabel.textContent = votes === 1 ? "vote" : "votes";
    total.append(count, voteLabel);
    const share = document.createElement("span");
    share.className = "candidate-share";
    share.textContent = `${percent.toFixed(1)}% of reported votes`;
    metrics.append(total, share);

    const track = document.createElement("div");
    track.className = "candidate-progress";
    track.setAttribute("role", "progressbar");
    track.setAttribute("aria-label", `${candidate.name} vote share`);
    track.setAttribute("aria-valuemin", "0");
    track.setAttribute("aria-valuemax", "100");
    track.setAttribute("aria-valuenow", percent.toFixed(1));
    track.setAttribute("aria-valuetext", `${fmt(votes)} votes, ${percent.toFixed(1)}%`);
    const bar = document.createElement("span");
    bar.className = "candidate-progress-fill";
    bar.style.width = `${percent}%`;
    track.append(bar);
    row.append(identity, metrics, track);
    container.append(row);
  }
  if (!reports.length) container.append(emptyState("No tally-centre declarations have been submitted for this view yet."));
}

function emptyState(message) {
  const empty = document.createElement("p");
  empty.className = "empty-state";
  empty.textContent = message;
  return empty;
}

function renderCentres() {
  const body = byId("centre-table");
  body.replaceChildren();
  const centers = centersInView();
  const latestByCenter = new Map(latestReportsForView().map((report) => [report.centerId, report]));
  const latestVotes = (report) => report
    ? Object.values(report.votes).reduce((sum, votes) => sum + votes, 0) : 0;
  if (!centers.length) {
    body.append(tableMessage(selectedPost() && selectedPost().isTemplate
      ? "Tally centres will appear when this post is configured."
      : "No tally centres match these filters."));
    return;
  }
  for (const center of centers) {
    const report = latestByCenter.get(center.id);
    const row = document.createElement("tr");
    const org = document.createElement("td");
    org.dataset.label = "College / school";
    org.textContent = centerUnitLabel(center);
    org.title = fullCenterUnit(center);
    const centerName = document.createElement("td");
    centerName.dataset.label = "Tally centre";
    centerName.textContent = center.name;
    const votes = document.createElement("td");
    votes.dataset.label = "Votes reported";
    votes.textContent = report ? fmt(latestVotes(report)) : "Awaiting";
    const time = document.createElement("td");
    time.dataset.label = "Last declaration";
    time.textContent = report ? dateLabel(report.recordedAt) : "No report";
    row.append(org, centerName, votes, time);
    body.append(row);
  }
}

function tableMessage(message) {
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.colSpan = 4;
  cell.className = "empty-state";
  cell.textContent = message;
  row.append(cell);
  return row;
}

function renderUpdates() {
  const list = byId("update-list");
  list.replaceChildren();
  const raceId = selectedRaceId();
  const centerMap = new Map(publicState.stations.map((center) => [center.id, center]));
  const postMap = new Map(publicState.races.map((post) => [post.id, postLabel(post)]));
  const allowedCenterIds = new Set(centersInView().map((center) => center.id));
  const reports = [...publicState.reports]
    .filter((report) => (!raceId || report.raceId === raceId) && allowedCenterIds.has(report.centerId))
    .sort((a, b) => b.id - a.id)
    .slice(0, 30);
  byId("update-count").textContent = `${reports.length} recent declaration${reports.length === 1 ? "" : "s"}`;
  byId("report-count").textContent = `${publicState.reports.length} submission${publicState.reports.length === 1 ? "" : "s"}`;
  if (!reports.length) {
    list.append(emptyState("No declarations to show yet."));
    return;
  }
  for (const report of reports) {
    const center = centerMap.get(report.centerId);
    const item = document.createElement("article");
    item.className = "update-item";
    const content = document.createElement("div");
    content.className = "update-content";
    const timeRow = document.createElement("div");
    timeRow.className = "update-time";
    const received = document.createElement("span");
    received.className = "update-kicker";
    received.textContent = "DECLARATION RECEIVED";
    const time = document.createElement("time");
    time.textContent = dateLabel(report.recordedAt);
    timeRow.append(received, time);

    const card = document.createElement("div");
    card.className = "update-card";
    const heading = document.createElement("div");
    heading.className = "update-heading";
    const name = document.createElement("strong");
    name.textContent = center ? center.name : "Tally centre";
    const type = document.createElement("span");
    type.className = "update-type";
    type.textContent = "FIELD REPORT";
    heading.append(name, type);

    const details = document.createElement("dl");
    details.className = "update-meta";
    const addDetail = (label, value, title = "") => {
      const field = document.createElement("div");
      const term = document.createElement("dt");
      term.textContent = label;
      const description = document.createElement("dd");
      description.textContent = value;
      if (title) description.title = title;
      field.append(term, description);
      details.append(field);
    };
    addDetail("Post", postMap.get(report.raceId) || "Post");
    addDetail(
      "College / school",
      center ? centerUnitLabel(center) : "Not specified",
      center ? fullCenterUnit(center) : ""
    );

    const footer = document.createElement("div");
    footer.className = "update-footer";
    const source = document.createElement("span");
    source.textContent = `Source: ${report.source}`;
    const reporter = document.createElement("span");
    reporter.textContent = `Reported by: ${report.reportedBy || "Field agent"}`;
    footer.append(source, reporter);
    card.append(heading, details, footer);
    content.append(timeRow, card);
    item.append(content);
    list.append(item);
  }
}

function renderWorkspace() {
  const panel = byId("operations-panel");
  const workspaceAvailable = Boolean(sessionUser && privateState);
  const operationsLink = document.querySelector('[data-page-target="operations"]');
  operationsLink.hidden = !workspaceAvailable;
  if (workspaceAvailable && !hadWorkspace && pageFromUrl() === "operations") {
    showAppPage("operations", false, false);
  } else if (!workspaceAvailable && (activePage === "operations" || pageFromUrl() === "operations")) {
    showAppPage("overview", false, false);
    updatePageUrl("overview", true);
  }
  hadWorkspace = workspaceAvailable;
  panel.hidden = !workspaceAvailable;
  byId("login-button").hidden = Boolean(sessionUser);
  byId("logout-button").hidden = !sessionUser;
  byId("user-label").hidden = !sessionUser;
  if (!workspaceAvailable) return;
  byId("user-label").textContent = sessionUser.name;
  byId("role-label").textContent = sessionUser.role;
  const isAdmin = sessionUser.role === "ADMIN";
  const mustChangePassword = Boolean(sessionUser.mustChangePassword);
  byId("workspace-heading").textContent = mustChangePassword
    ? "Secure your account"
    : isAdmin ? "Election administration" : "Field-agent workspace";
  byId("admin-workspace").hidden = !isAdmin || mustChangePassword;
  byId("agent-workspace").hidden = isAdmin || mustChangePassword;
  byId("password-required").hidden = !mustChangePassword;
  byId("password-form").classList.toggle("password-form-required", mustChangePassword);
  byId("password-form").querySelector("h3").textContent = mustChangePassword
    ? "Set a personal password"
    : "Change your password";
  byId("password-form").querySelector('[name="currentPassword"]').autocomplete =
    mustChangePassword ? "off" : "current-password";
  byId("password-form").querySelector('[name="currentPassword"]').setAttribute(
    "aria-label", mustChangePassword ? "One-time password" : "Current password"
  );
  byId("password-form").querySelector("label").firstChild.textContent =
    mustChangePassword ? "One-time password" : "Current password";
  const candidatePostSelect = byId("candidate-race");
  const canAddCandidate = privateState.races.length > 0;
  fillSelect(
    candidatePostSelect,
    privateState.races,
    canAddCandidate ? "Select a post" : "Add a post first",
    postLabel
  );
  candidatePostSelect.disabled = !canAddCandidate;
  byId("candidate-name").disabled = !canAddCandidate;
  byId("candidate-post-help").hidden = canAddCandidate;
  byId("candidate-post-help").textContent = canAddCandidate
    ? ""
    : "Add an official post before adding candidates.";
  byId("add-post-first-link").hidden = canAddCandidate;
  byId("candidate-submit").disabled = !canAddCandidate;
  fillSelect(byId("report-race"), privateState.races, "Select a post", postLabel);
  fillSelect(byId("agent-center"), privateState.stations, "Assign tally centre");
  byId("agent-center").disabled = !privateState.stations.length;
  byId("agent-create-button").disabled = !privateState.stations.length;
  byId("agent-centre-help").hidden = Boolean(privateState.stations.length);
  byId("agent-centre-help").textContent = privateState.stations.length
    ? ""
    : "Register a tally centre before creating a field-agent account.";
  if (!byId("candidate-race").value && privateState.races.length) {
    byId("candidate-race").value = String(privateState.races[0].id);
  }
  if (!byId("report-race").value && privateState.races.length) {
    byId("report-race").value = String(privateState.races[0].id);
  }
  if (!byId("agent-center").value && privateState.stations.length) {
    byId("agent-center").value = String(privateState.stations[0].id);
  }
  const reportCenters = isAdmin
    ? privateState.stations
    : privateState.stations.filter((center) => center.id === sessionUser.centerId);
  fillSelect(byId("report-center"), reportCenters, "Select assigned centre");
  if (!isAdmin && sessionUser.centerId) byId("report-center").value = String(sessionUser.centerId);
  const assignedCenter = privateState.stations.find((center) => center.id === sessionUser.centerId);
  byId("agent-name").textContent = sessionUser.name;
  byId("assigned-centre").textContent = assignedCenter ? assignedCenter.name : "Not assigned";
  activateAgentTab(activeAgentTab);
  renderReportFields();
  if (isAdmin) {
    renderCenterDirectory();
    renderAgents();
    renderIncidents();
  } else {
    renderAgentObservations();
    renderAgentActivity();
  }
}

function fillSelect(select, items, placeholder, labelFor = (item) => item.name) {
  const selected = select.value;
  select.replaceChildren();
  const prompt = document.createElement("option");
  prompt.value = "";
  prompt.textContent = placeholder;
  select.append(prompt);
  for (const item of items) {
    const option = document.createElement("option");
    option.value = String(item.id);
    option.textContent = labelFor(item);
    select.append(option);
  }
  if (items.some((item) => String(item.id) === selected)) select.value = selected;
}

function renderReportFields() {
  const container = byId("report-candidates");
  if (!container) return;
  container.replaceChildren();
  const raceId = Number(byId("report-race").value);
  const candidates = privateState
    ? privateState.candidates.filter((candidate) => candidate.raceId === raceId)
    : [];
  for (const candidate of candidates) {
    const label = document.createElement("label");
    label.className = "candidate-entry-card";
    const name = document.createElement("strong");
    name.className = "candidate-entry-name";
    name.textContent = candidate.name;
    const votesLabel = document.createElement("span");
    votesLabel.className = "candidate-entry-label";
    votesLabel.textContent = "Votes";
    const input = document.createElement("input");
    input.type = "number";
    input.name = `vote_${candidate.id}`;
    input.min = "0";
    input.step = "1";
    input.inputMode = "numeric";
    input.required = true;
    input.placeholder = "0";
    input.className = "candidate-entry-input";
    input.setAttribute("aria-label", `Votes for ${candidate.name}`);
    const entry = document.createElement("div");
    entry.className = "candidate-entry-value";
    entry.append(votesLabel, input);
    label.replaceChildren(name, entry);
    container.append(label);
  }
  if (!candidates.length) container.append(emptyState("Select a post with candidates."));
}

function renderAgents() {
  const list = byId("agent-list");
  list.replaceChildren();
  const centers = new Map(privateState.stations.map((center) => [center.id, center]));
  const activeCount = privateState.users.filter((agent) => agent.enabled).length;
  byId("agent-account-count").textContent =
    `${activeCount} active · ${privateState.users.length - activeCount} disabled`;
  if (!privateState.users.length) {
    list.append(emptyState("No agent accounts yet."));
    return;
  }
  for (const agent of [...privateState.users].sort((a, b) => a.name.localeCompare(b.name))) {
    const card = document.createElement("article");
    card.className = "account-card";
    const heading = document.createElement("div");
    heading.className = "account-heading";
    const identity = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = agent.name;
    const username = document.createElement("small");
    username.textContent = `@${agent.username}`;
    identity.append(name, username);
    const status = document.createElement("span");
    status.className = `account-status${agent.enabled ? " is-active" : " is-disabled"}`;
    status.textContent = agent.enabled ? "Active" : "Disabled";
    heading.append(identity, status);
    card.append(heading);

    const assignment = document.createElement("form");
    assignment.className = "agent-assignment";
    assignment.dataset.agentId = String(agent.id);
    const select = document.createElement("select");
    select.name = "centerId";
    select.setAttribute("aria-label", `Assigned tally centre for ${agent.name}`);
    for (const center of privateState.stations) {
      const option = document.createElement("option");
      option.value = String(center.id);
      option.textContent = center.name;
      select.append(option);
    }
    select.value = String(agent.centerId);
    select.disabled = !privateState.stations.length;
    const save = document.createElement("button");
    save.className = "button button-outline";
    save.type = "submit";
    save.textContent = "Save centre";
    save.disabled = !privateState.stations.length || select.value === String(agent.centerId);
    select.addEventListener("change", () => {
      save.disabled = select.value === String(agent.centerId);
    });
    assignment.append(select, save);
    card.append(assignment);

    const controls = document.createElement("div");
    controls.className = "account-actions";
    if (agent.enabled) {
      const reset = document.createElement("button");
      reset.className = "button button-outline";
      reset.type = "button";
      reset.dataset.resetAgent = String(agent.id);
      reset.dataset.agentName = agent.name;
      reset.textContent = "Reset password";
      controls.append(reset);
      const disable = document.createElement("button");
      disable.className = "text-button";
      disable.type = "button";
      disable.dataset.agentAction = "disable";
      disable.dataset.agentId = String(agent.id);
      disable.textContent = "Disable account";
      controls.append(disable);
    } else {
      const enable = document.createElement("button");
      enable.className = "button button-outline";
      enable.type = "button";
      enable.dataset.agentAction = "enable";
      enable.dataset.agentId = String(agent.id);
      enable.textContent = "Reactivate account";
      controls.append(enable);
    }
    if (agent.mustChangePassword && agent.enabled) {
      const notice = document.createElement("small");
      notice.className = "account-notice";
      notice.textContent = "Password change required at next sign-in";
      controls.append(notice);
    }
    card.append(controls);
    list.append(card);
  }
}

function makeObservationCard(incident, centers, canManage) {
  const card = document.createElement("article");
  card.className = `observation-card${incident.priority === "URGENT" ? " is-urgent" : ""}`;
  const heading = document.createElement("div");
  heading.className = "observation-heading";
  const labels = document.createElement("div");
  labels.className = "observation-labels";
  const category = document.createElement("strong");
  category.textContent = OBSERVATION_CATEGORIES[incident.category] || "Field observation";
  const priority = document.createElement("span");
  priority.className = `priority-tag${incident.priority === "URGENT" ? " is-urgent" : ""}`;
  priority.textContent = incident.priority === "URGENT" ? "Urgent" : "Routine";
  const status = document.createElement("span");
  status.className = `observation-status${incident.status === "RESOLVED" ? " is-resolved" : ""}`;
  status.textContent = incident.status === "RESOLVED" ? "Resolved" : "Open";
  labels.append(category, priority, status);
  const date = document.createElement("time");
  date.dateTime = incident.createdAt;
  date.textContent = dateLabel(incident.createdAt);
  heading.append(labels, date);
  const description = document.createElement("p");
  description.className = "observation-description-text";
  description.textContent = incident.description;
  const meta = document.createElement("small");
  const centerName = centers.get(incident.centerId) || "General";
  meta.textContent = `${centerName} · Reported by ${incident.reportedBy || "Field agent"}`;
  card.append(heading, description, meta);
  if (incident.status === "RESOLVED" && incident.resolvedAt) {
    const resolution = document.createElement("p");
    resolution.className = "observation-resolution";
    resolution.textContent = incident.resolution
      ? `Resolution: ${incident.resolution}`
      : `Resolved ${dateLabel(incident.resolvedAt)}${incident.resolvedBy ? ` by ${incident.resolvedBy}` : ""}`;
    card.append(resolution);
    if (incident.resolution && incident.resolvedBy) {
      const resolvedBy = document.createElement("small");
      resolvedBy.className = "resolution-meta";
      resolvedBy.textContent = `Resolved ${dateLabel(incident.resolvedAt)} by ${incident.resolvedBy}`;
      card.append(resolvedBy);
    }
  }
  if (canManage) {
    const actions = document.createElement("div");
    actions.className = "observation-actions";
    const button = document.createElement("button");
    button.className = "button button-outline";
    button.type = "button";
    button.dataset.incidentId = String(incident.id);
    if (incident.status === "OPEN") {
      button.dataset.incidentAction = "resolve";
      button.textContent = "Record resolution";
    } else {
      button.dataset.incidentAction = "reopen";
      button.textContent = "Reopen observation";
    }
    actions.append(button);
    card.append(actions);
  }
  return card;
}

function renderAgentObservations() {
  const list = byId("agent-observation-list");
  const incidents = [...privateState.incidents].sort((a, b) => b.id - a.id);
  const openCount = incidents.filter((incident) => incident.status === "OPEN").length;
  byId("agent-observation-count").textContent =
    `${incidents.length} observation${incidents.length === 1 ? "" : "s"}`;
  byId("agent-open-observation-count").textContent = fmt(openCount);
  list.replaceChildren();
  if (!incidents.length) {
    list.append(emptyState("No observations have been logged for this centre."));
    return;
  }
  const centers = new Map(privateState.stations.map((center) => [center.id, center.name]));
  for (const incident of incidents.slice(0, 100)) {
    list.append(makeObservationCard(incident, centers, false));
  }
}

function activateAgentTab(tabName) {
  const tabs = [...document.querySelectorAll("[data-agent-tab]")];
  if (!tabs.some((tab) => tab.dataset.agentTab === tabName)) tabName = "report";
  activeAgentTab = tabName;
  for (const tab of tabs) {
    const selected = tab.dataset.agentTab === tabName;
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
    byId(`agent-${tab.dataset.agentTab}-panel`).hidden = !selected;
    tab.classList.toggle("is-selected", selected);
  }
}

function renderAgentActivity() {
  const list = byId("agent-activity-list");
  list.replaceChildren();
  const reports = [...privateState.reports]
    .filter((report) => report.centerId === sessionUser.centerId)
    .sort((a, b) => b.id - a.id);
  byId("agent-report-count").textContent = fmt(reports.length);
  if (!reports.length) {
    list.append(emptyState("No declarations have been submitted for this centre yet."));
    return;
  }
  const posts = new Map(privateState.races.map((post) => [post.id, postLabel(post)]));
  const candidates = new Map(privateState.candidates.map((candidate) => [candidate.id, candidate.name]));
  for (const report of reports.slice(0, 100)) {
    const card = document.createElement("article");
    card.className = "agent-activity-card";
    const heading = document.createElement("div");
    heading.className = "update-heading";
    const post = document.createElement("strong");
    post.textContent = posts.get(report.raceId) || "Election post";
    const date = document.createElement("time");
    date.dateTime = report.recordedAt;
    date.textContent = dateLabel(report.recordedAt);
    heading.append(post, date);
    const source = document.createElement("p");
    source.textContent = `Source: ${report.source}`;
    const totals = document.createElement("ul");
    totals.className = "agent-activity-totals";
    for (const [candidateId, votes] of Object.entries(report.votes)) {
      const item = document.createElement("li");
      const candidate = document.createElement("span");
      candidate.textContent = candidates.get(Number(candidateId)) || "Candidate";
      const count = document.createElement("strong");
      count.textContent = fmt(votes);
      item.append(candidate, count);
      totals.append(item);
    }
    card.append(heading, source, totals);
    list.append(card);
  }
}

function renderIncidents() {
  const list = byId("incident-list");
  list.replaceChildren();
  const centers = new Map(privateState.stations.map((center) => [center.id, center.name]));
  const incidents = [...privateState.incidents].sort((a, b) => b.id - a.id);
  const openCount = incidents.filter((incident) => incident.status === "OPEN").length;
  const urgentCount = incidents.filter((incident) =>
    incident.status === "OPEN" && incident.priority === "URGENT").length;
  const resolvedCount = incidents.length - openCount;
  byId("incident-summary").textContent =
    `${openCount} open · ${urgentCount} urgent · ${resolvedCount} resolved`;
  const filter = byId("incident-filter").value;
  const visible = incidents.filter((incident) => filter === "ALL" || incident.status === filter);
  if (!visible.length) {
    list.append(emptyState(filter === "OPEN" ? "No open observations need follow-up."
      : filter === "RESOLVED" ? "No resolved observations yet." : "No field observations."));
    return;
  }
  for (const incident of visible.slice(0, 100)) {
    list.append(makeObservationCard(incident, centers, true));
  }
}

async function postForm(path, formOrValues) {
  const values = formOrValues instanceof HTMLFormElement
    ? new URLSearchParams(new FormData(formOrValues))
    : formOrValues instanceof URLSearchParams ? formOrValues : new URLSearchParams(formOrValues);
  const response = await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      "X-Requested-With": "fetch"
    },
    body: values
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "The request could not be completed.");
  if (result.races) privateState = result;
  return result;
}

async function refreshState() {
  const publicResponse = await fetch("/api/public-state", { cache: "no-store" });
  if (!publicResponse.ok) throw new Error("Public results are temporarily unavailable.");
  publicState = await publicResponse.json();
  const sessionResponse = await fetch("/api/session", { cache: "no-store" });
  const session = await sessionResponse.json();
  sessionUser = session.user;
  privateState = null;
  if (sessionUser) {
    const response = await fetch("/api/state", { cache: "no-store" });
    if (response.ok) privateState = await response.json();
    else sessionUser = null;
  }
  render();
}

function bindForm(id, endpoint, message, done) {
  byId(id).addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    if (!form.reportValidity()) return;
    try {
      await postForm(endpoint, form);
      form.reset();
      await refreshState();
      if (done) done();
      showToast(message);
    } catch (error) {
      showToast(error.message || "Something went wrong.", true);
    }
  });
}

byId("login-button").addEventListener("click", () => byId("login-dialog").showModal());
for (const button of document.querySelectorAll("[data-close]")) {
  button.addEventListener("click", () => button.closest("dialog").close());
}

byId("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    await postForm("/api/login", form);
    form.reset();
    byId("login-dialog").close();
    await refreshState();
    showAppPage("operations");
    showToast("Signed in successfully.");
  } catch (error) {
    showToast(error.message || "Sign-in failed.", true);
  }
});

byId("logout-button").addEventListener("click", async () => {
  try {
    await postForm("/api/logout", {});
    sessionUser = null;
    privateState = null;
    render();
    showToast("Signed out.");
  } catch (error) {
    showToast(error.message || "Could not sign out.", true);
  }
});

for (const select of Object.values(filters)) {
  select.addEventListener("change", () => {
    if (select === filters.college) filters.school.value = "";
    render();
  });
}

byId("center-college").addEventListener("change", () => {
  if (byId("center-college").value) byId("center-school").value = "";
  renderCenterDirectory();
});
byId("center-school").addEventListener("change", renderCenterDirectory);
byId("report-race").addEventListener("change", renderReportFields);
for (const tab of document.querySelectorAll("[data-agent-tab]")) {
  tab.addEventListener("click", () => activateAgentTab(tab.dataset.agentTab));
}
byId("agent-workspace").querySelector('[role="tablist"]').addEventListener("keydown", (event) => {
  const tabs = [...document.querySelectorAll("[data-agent-tab]")];
  const index = tabs.indexOf(event.target);
  if (index < 0) return;
  const next = event.key === "ArrowRight" ? (index + 1) % tabs.length
    : event.key === "ArrowLeft" ? (index - 1 + tabs.length) % tabs.length
    : event.key === "Home" ? 0
    : event.key === "End" ? tabs.length - 1
    : -1;
  if (next < 0) return;
  event.preventDefault();
  tabs[next].focus();
  activateAgentTab(tabs[next].dataset.agentTab);
});
bindForm("report-form", "/api/tallies", "Declaration published to the live updates.", () => {
  activateAgentTab("activity");
});
byId("incident-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity() || !sessionUser) return;
  const values = new URLSearchParams(new FormData(form));
  if (sessionUser.centerId) values.set("centerId", String(sessionUser.centerId));
  try {
    await postForm("/api/incidents", values);
    form.reset();
    await refreshState();
    activateAgentTab("observations");
    showToast("Observation added to the restricted log.");
  } catch (error) {
    showToast(error.message || "Could not save the observation.", true);
  }
});
bindForm("race-form", "/api/races", "Post added.");
bindForm("candidate-form", "/api/candidates", "Candidate added.");
byId("center-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const values = new URLSearchParams(new FormData(form));
  if (values.get("college") === OTHER_UNIT_VALUE) {
    values.set("college", values.get("otherCollege") || "");
  }
  if (values.get("school") === OTHER_SCHOOL_VALUE) {
    values.set("school", values.get("otherSchool") || "");
  }
  if (values.get("college")) values.set("school", "");
  try {
    await postForm("/api/stations", values);
    form.reset();
    await refreshState();
    showToast("Tally centre added.");
  } catch (error) {
    showToast(error.message || "Could not add the tally centre.", true);
  }
});
bindForm("agent-form", "/api/agents", "Agent account created.");
bindForm("password-form", "/api/account/password", "Password changed.");

byId("agent-list").addEventListener("click", async (event) => {
  const reset = event.target.closest("[data-reset-agent]");
  if (reset) {
    const form = byId("agent-reset-form");
    form.reset();
    form.elements.id.value = reset.dataset.resetAgent;
    byId("agent-reset-agent").textContent =
      `Set a one-time password for ${reset.dataset.agentName}. They will be signed out and must choose a new password before working.`;
    byId("agent-reset-dialog").showModal();
    return;
  }
  const button = event.target.closest("[data-agent-action]");
  if (!button) return;
  const enabling = button.dataset.agentAction === "enable";
  if (!window.confirm(enabling
    ? "Reactivate this account? The agent will need to sign in again."
    : "Disable this account and sign out its active sessions?")) return;
  button.disabled = true;
  try {
    await postForm(`/api/agents/${enabling ? "enable" : "disable"}`, {
      id: button.dataset.agentId
    });
    await refreshState();
    showToast(enabling ? "Agent account reactivated." : "Agent account disabled.");
  } catch (error) {
    button.disabled = false;
    showToast(error.message || "Could not update the account.", true);
  }
});

byId("agent-list").addEventListener("submit", async (event) => {
  const form = event.target.closest(".agent-assignment");
  if (!form) return;
  event.preventDefault();
  const values = new URLSearchParams(new FormData(form));
  values.set("id", form.dataset.agentId);
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await postForm("/api/agents/assignment", values);
    await refreshState();
    showToast("Agent centre updated; their active sessions were signed out.");
  } catch (error) {
    button.disabled = false;
    showToast(error.message || "Could not update the assignment.", true);
  }
});

byId("agent-reset-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  if (form.elements.password.value !== form.elements.confirmPassword.value) {
    showToast("The one-time passwords do not match.", true);
    form.elements.confirmPassword.focus();
    return;
  }
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await postForm("/api/agents/reset-password", form);
    byId("agent-reset-dialog").close();
    form.reset();
    await refreshState();
    showToast("Password reset. The agent must change it at next sign-in.");
  } catch (error) {
    showToast(error.message || "Could not reset the password.", true);
  } finally {
    submit.disabled = false;
  }
});

byId("incident-filter").addEventListener("change", renderIncidents);
byId("incident-list").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-incident-action]");
  if (!button) return;
  if (button.dataset.incidentAction === "resolve") {
    const form = byId("incident-resolution-form");
    form.reset();
    form.elements.id.value = button.dataset.incidentId;
    byId("incident-resolution-dialog").showModal();
    return;
  }
  if (!window.confirm("Reopen this observation for follow-up?")) return;
  button.disabled = true;
  try {
    await postForm("/api/incidents/reopen", { id: button.dataset.incidentId });
    await refreshState();
    showToast("Observation reopened.");
  } catch (error) {
    button.disabled = false;
    showToast(error.message || "Could not update the observation.", true);
  }
});

byId("incident-resolution-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await postForm("/api/incidents/resolve", form);
    byId("incident-resolution-dialog").close();
    form.reset();
    await refreshState();
    showToast("Observation marked resolved.");
  } catch (error) {
    showToast(error.message || "Could not resolve the observation.", true);
  } finally {
    submit.disabled = false;
  }
});

const events = new EventSource("/api/events");
events.addEventListener("open", () => {
  byId("connection-label").textContent = "LIVE";
  byId("connection-dot").classList.add("connected");
});
events.addEventListener("error", () => {
  byId("connection-label").textContent = "RECONNECTING";
  byId("connection-dot").classList.remove("connected");
});
events.addEventListener("update", () => refreshState().catch(() => {}));
window.setInterval(() => refreshState().catch(() => {}), 30000);

refreshState().catch((error) => {
  byId("connection-label").textContent = "OFFLINE";
  showToast(error.message || "Could not load live updates.", true);
});
