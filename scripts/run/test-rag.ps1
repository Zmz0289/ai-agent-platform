$ErrorActionPreference = 'Stop'

# 输出按 UTF-8 显示，避免中文乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 问题直接从 question.txt 读取（UTF-8），改问题只需要改这个文件
$questionFile = Join-Path $PSScriptRoot 'question.txt'
if (-not (Test-Path $questionFile)) {
    throw "Missing question file: $questionFile"
}

$question = (Get-Content -Raw -Encoding UTF8 $questionFile).Trim()
if (-not $question) {
    throw "Question file is empty: $questionFile"
}

$q = [uri]::EscapeDataString($question)

$s = Invoke-RestMethod -Method Post http://localhost:8081/api/v1/agent/session
$s.sessionId

$r = Invoke-WebRequest -Method Post -UseBasicParsing -Uri "http://localhost:8081/api/v1/agent/chat?sessionId=$($s.sessionId)&question=$q&useRag=true"
[System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json | Select-Object -ExpandProperty answer
