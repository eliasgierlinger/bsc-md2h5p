/*
 * The page for `md2h5p serve`. Both forms post with fetch and get JSON back
 * ({ title, fileName, downloadUrl, warnings } or { error }), so the conversion warnings can be
 * shown next to the download link instead of only on the server's console.
 */

// ---------- tabs ----------

const tabs = document.querySelectorAll('.tab');

tabs.forEach(function (tab) {
  tab.addEventListener('click', function () {
    tabs.forEach(function (other) {
      const selected = other === tab;
      other.setAttribute('aria-selected', String(selected));
      document.getElementById(other.getAttribute('aria-controls')).hidden = !selected;
    });
  });
});

// ---------- the file the conversion produced ----------

const result = document.getElementById('result');
const resultTitle = document.getElementById('resultTitle');
const resultFileName = document.getElementById('resultFileName');
const downloadLink = document.getElementById('downloadLink');
const warningsBox = document.getElementById('warnings');
const errorBox = document.getElementById('errorBox');

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = false;
  result.hidden = true;
  errorBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function showResult(data) {
  errorBox.hidden = true;
  resultTitle.textContent = 'Converted “' + data.title + '”';
  resultFileName.textContent = data.fileName;
  downloadLink.href = data.downloadUrl;
  downloadLink.setAttribute('download', data.fileName);
  renderWarnings(data.warnings);
  result.hidden = false;
  result.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function renderWarnings(warnings) {
  warningsBox.innerHTML = '';

  if (!warnings || warnings.length === 0) {
    const ok = document.createElement('p');
    ok.className = 'notice notice--ok';
    ok.textContent = 'No warnings. Everything the source referenced was converted.';
    warningsBox.appendChild(ok);
    return;
  }

  const details = document.createElement('details');
  details.className = 'warnings';
  details.open = true;

  const summary = document.createElement('summary');
  summary.textContent = warnings.length === 1 ? '1 warning' : warnings.length + ' warnings';
  details.appendChild(summary);

  const list = document.createElement('ol');
  warnings.forEach(function (warning) {
    const item = document.createElement('li');
    item.textContent = warning;
    list.appendChild(item);
  });
  details.appendChild(list);

  const explain = document.createElement('p');
  explain.className = 'explain';
  explain.textContent = 'The package was still created. Each warning names something that was ' +
    'skipped or rendered differently because H5P does not accept it.';
  details.appendChild(explain);

  warningsBox.appendChild(details);
}

/** Runs one conversion request, keeping the button that started it busy until the answer is in. */
async function convert(url, body, button, statusElement) {
  const label = button.textContent;
  button.disabled = true;
  button.textContent = 'Converting…';
  statusElement.innerHTML = '<span class="spinner"></span>Converting…';

  try {
    const response = await fetch(url, { method: 'POST', body: body });
    const data = await response.json().catch(function () {
      return { error: 'The server answered with ' + response.status + ' ' + response.statusText + '.' };
    });

    if (!response.ok || data.error) {
      showError(data.error || 'Conversion failed.');
    } else {
      showResult(data);
    }
  } catch (e) {
    showError('Could not reach the server: ' + e.message);
  } finally {
    button.disabled = false;
    button.textContent = label;
    statusElement.textContent = '';
  }
}

// ---------- git repository ----------

const repoForm = document.getElementById('repoForm');

repoForm.addEventListener('submit', function (e) {
  e.preventDefault();
  const repoUrl = document.getElementById('repoUrl').value.trim();
  if (!repoUrl) {
    showError('Enter the URL of a public Git repository.');
    return;
  }
  const body = new URLSearchParams();
  body.append('repoUrl', repoUrl);
  body.append('subfolder', document.getElementById('subfolder').value.trim());
  body.append('title', document.getElementById('repoTitle').value.trim());
  convert('/convert/repo', body, document.getElementById('repoSubmit'), document.getElementById('repoStatus'));
});

// ---------- uploaded files ----------

const selectedFiles = [];

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function addFile(file, path) {
  selectedFiles.push({ file: file, path: path || file.name });
  renderFileList();
}

function renderFileList() {
  const list = document.getElementById('fileList');
  const count = document.getElementById('fileCount');
  list.innerHTML = '';

  selectedFiles.forEach(function (entry, index) {
    const li = document.createElement('li');

    const path = document.createElement('span');
    path.className = 'path';
    path.textContent = entry.path;
    li.appendChild(path);

    const size = document.createElement('span');
    size.className = 'size';
    size.textContent = formatSize(entry.file.size);
    li.appendChild(size);

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'button--link';
    remove.textContent = 'remove';
    remove.setAttribute('aria-label', 'Remove ' + entry.path);
    remove.onclick = function () {
      selectedFiles.splice(index, 1);
      renderFileList();
    };
    li.appendChild(remove);

    list.appendChild(li);
  });

  if (selectedFiles.length === 0) {
    count.textContent = '';
  } else {
    count.textContent = selectedFiles.length === 1 ? '1 file selected' : selectedFiles.length + ' files selected';
  }
}

document.getElementById('addFilesButton').addEventListener('click', function () {
  document.getElementById('fileInput').click();
});
document.getElementById('addFolderButton').addEventListener('click', function () {
  document.getElementById('folderInput').click();
});

document.getElementById('fileInput').addEventListener('change', function (e) {
  for (const file of e.target.files) addFile(file, file.name);
  e.target.value = '';
});

document.getElementById('folderInput').addEventListener('change', function (e) {
  for (const file of e.target.files) addFile(file, file.webkitRelativePath || file.name);
  e.target.value = '';
});

function traverseEntry(entry, prefix) {
  return new Promise(function (resolve) {
    if (entry.isFile) {
      entry.file(function (file) {
        addFile(file, prefix + entry.name);
        resolve();
      });
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      const entries = [];
      function readBatch() {
        reader.readEntries(async function (batch) {
          if (batch.length === 0) {
            for (const child of entries) await traverseEntry(child, prefix + entry.name + '/');
            resolve();
          } else {
            entries.push(...batch);
            readBatch();
          }
        });
      }
      readBatch();
    } else {
      resolve();
    }
  });
}

const dropZone = document.getElementById('dropZone');

dropZone.addEventListener('dragover', function (e) {
  e.preventDefault();
  dropZone.classList.add('is-dragging');
});
dropZone.addEventListener('dragleave', function (e) {
  // Moving onto the text inside the zone fires dragleave on the zone itself, so only a pointer
  // that has actually left the box counts.
  if (!dropZone.contains(e.relatedTarget)) dropZone.classList.remove('is-dragging');
});
dropZone.addEventListener('drop', function (e) {
  e.preventDefault();
  dropZone.classList.remove('is-dragging');

  const items = e.dataTransfer.items;
  if (!items || items.length === 0 || !items[0].webkitGetAsEntry) {
    for (const file of e.dataTransfer.files) addFile(file, file.name);
    return;
  }

  // Every entry has to be taken out of the DataTransfer *now*: the list is only valid for as long
  // as this handler runs synchronously, and the first `await` empties it - so awaiting inside the
  // loop read one entry and got null for every file after it. Dropping five files added one.
  const entries = [];
  for (const item of items) {
    const entry = item.webkitGetAsEntry();
    if (entry) entries.push(entry);
  }

  if (entries.length === 0) {
    for (const file of e.dataTransfer.files) addFile(file, file.name);
    return;
  }

  (async function () {
    for (const entry of entries) await traverseEntry(entry, '');
  })();
});

// ---------- existing package ----------

// One file, chosen through the dialog or dropped onto the picker - both land here rather than in
// the input's file list, which a drop cannot fill in every browser.
let existingPackage = null;

function setExistingPackage(file) {
  if (file && !/\.h5p$/i.test(file.name)) {
    showError('The package to update has to be a .h5p file, not ' + file.name + '.');
    return;
  }
  existingPackage = file || null;
  document.getElementById('packageName').textContent = existingPackage
    ? existingPackage.name + ' (' + formatSize(existingPackage.size) + ')'
    : 'or drop a .h5p file';
  document.getElementById('removePackageButton').hidden = !existingPackage;
}
setExistingPackage(null);

document.getElementById('choosePackageButton').addEventListener('click', function () {
  document.getElementById('existingPackageInput').click();
});
document.getElementById('existingPackageInput').addEventListener('change', function (e) {
  setExistingPackage(e.target.files[0]);
  e.target.value = '';
});
document.getElementById('removePackageButton').addEventListener('click', function () {
  setExistingPackage(null);
});

const packageZone = document.getElementById('packageZone');

packageZone.addEventListener('dragover', function (e) {
  e.preventDefault();
  packageZone.classList.add('is-dragging');
});
packageZone.addEventListener('dragleave', function (e) {
  if (!packageZone.contains(e.relatedTarget)) packageZone.classList.remove('is-dragging');
});
packageZone.addEventListener('drop', function (e) {
  e.preventDefault();
  packageZone.classList.remove('is-dragging');
  const files = e.dataTransfer.files;
  if (files.length > 1) {
    showError('Drop a single .h5p file - only one package can be updated at a time.');
    return;
  }
  if (files.length === 1) setExistingPackage(files[0]);
});

document.getElementById('uploadSubmitButton').addEventListener('click', function () {
  if (selectedFiles.length === 0) {
    showError('Add at least one Markdown file, or drop a folder.');
    return;
  }
  const formData = new FormData();
  formData.append('title', document.getElementById('uploadTitle').value.trim());
  for (const entry of selectedFiles) {
    formData.append('files', entry.file, entry.path);
  }

  // Sent under its own field name so the server never mistakes the package for a chapter.
  if (existingPackage) {
    formData.append('existingPackage', existingPackage, existingPackage.name);
  }

  convert(
    '/convert/upload',
    formData,
    document.getElementById('uploadSubmitButton'),
    document.getElementById('uploadStatus')
  );
});
