<#
.SYNOPSIS
Adds baseline developer KDoc/JSDoc to undocumented production declarations.

.DESCRIPTION
The script is intentionally additive: a declaration with a KDoc/JSDoc block in
the preceding five lines is left untouched. Descriptions are derived from the
declaration verb and complement, rather than replace, the detailed file-level
and public-API contracts in docs/development/. Re-run after adding declarations.
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot ".."))
)

$ErrorActionPreference = 'Stop'

function Has-PrecedingDocumentation([System.Collections.Generic.List[string]]$lines, [int]$index) {
    for ($cursor = $index - 1; $cursor -ge 0; $cursor--) {
        $line = $lines[$cursor].Trim()
        if ($line -eq '' -or $line.StartsWith('@')) { continue }
        if ($line -eq '*/') {
            for ($commentCursor = $cursor - 1; $commentCursor -ge 0; $commentCursor--) {
                if ($lines[$commentCursor].Trim() -eq '/**') { return $true }
            }
        }
        return $false
    }
    return $false
}

function Is-DocumentedDeclarationLine([string]$line) {
    return $line -match '^(?:public |private |protected |internal |export |default |abstract |open |data |sealed |final |async |suspend )*(?:class|interface|object|enum class|type)\s+[A-Za-z_]' -or
        $line -match '^(?:public |private |protected |internal |export |default |async |suspend )*fun\s+' -or
        $line -match '^(?:export\s+)?(?:async\s+)?function\s+[A-Za-z_]' -or
        $line -match '^(?:export\s+)?(?:const|let)\s+[A-Za-z_]\w*\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_]\w*)\s*=>' -or
        $line -match '^\s{2}(?:public |private |protected |static |readonly |override |async )*(?:constructor|[A-Za-z_]\w*)\s*(?:<[^>]+>)?\s*\('
}

function Remove-OrphanedGeneratedDocumentation([System.Collections.Generic.List[string]]$lines) {
    $removed = 0
    for ($index = $lines.Count - 3; $index -ge 0; $index--) {
        if ($lines[$index].Trim() -ne '/**' -or $lines[$index + 2].Trim() -ne '*/') { continue }
        if ($lines[$index + 1] -notmatch '^\s*\* (?:Defines the |Retrieves or enumerates|Creates or persists|Updates the |Removes, stops, or releases|Parses external|Converts a value|Validates or normalizes|Evaluates the named|Initializes, connects, or acquires|Delivers or routes|Processes the named|Implements `)') { continue }
        $next = $index + 3
        while ($next -lt $lines.Count -and $lines[$next].Trim() -eq '') { $next++ }
        if ($next -ge $lines.Count -or -not (Is-DocumentedDeclarationLine $lines[$next])) {
            $lines.RemoveRange($index, 3)
            $removed++
        }
    }
    return $removed
}

function Describe-Declaration([string]$name, [string]$kind) {
    if ($kind -ne 'function') { return "Defines the $kind ``$name``, the contract implemented by this source unit." }
    $patterns = @(
        @{ Regex = '^(list|find|get|load|read|query)'; Text = 'Retrieves or enumerates the requested state.' },
        @{ Regex = '^(create|register|add|insert|persist|save)'; Text = 'Creates or persists the requested state.' },
        @{ Regex = '^(update|patch|set|replace)'; Text = 'Updates the requested state.' },
        @{ Regex = '^(delete|remove|destroy|disconnect|stop)'; Text = 'Removes, stops, or releases the requested state.' },
        @{ Regex = '^(parse|deserialize|decode)'; Text = 'Parses external or persisted input into the required representation.' },
        @{ Regex = '^(serialize|encode|to[A-Z])'; Text = 'Converts a value into the representation required by its caller.' },
        @{ Regex = '^(validate|require|normalize|sanitize)'; Text = 'Validates or normalizes input before the caller continues.' },
        @{ Regex = '^(is|has|can|should|owns|requires)'; Text = 'Evaluates the named capability or state predicate.' },
        @{ Regex = '^(start|onStart|initialize|init|connect|spawn|acquire)'; Text = 'Initializes, connects, or acquires the named runtime resource.' },
        @{ Regex = '^(dispatch|send|publish|emit|write|route)'; Text = 'Delivers or routes the named data to its configured destination.' },
        @{ Regex = '^(handle|process|execute|run|evaluate)'; Text = 'Processes the named request, event, or operation.' }
    )
    foreach ($pattern in $patterns) { if ($name -match $pattern.Regex) { return $pattern.Text } }
    return "Implements ``$name`` for this source unit."
}

$sourceRoots = @(
    (Join-Path $RepositoryRoot 'backend/src/main/kotlin')
    (Join-Path $RepositoryRoot 'frontend/src')
)
$changedFiles = 0
$documentedDeclarations = 0

foreach ($root in $sourceRoots) {
    Get-ChildItem -Path $root -Recurse -File -Include '*.kt', '*.ts', '*.tsx' | Where-Object {
        $_.FullName -notmatch '[\\/]__tests__[\\/]'
    } | ForEach-Object {
        $file = $_
        $lines = [System.Collections.Generic.List[string]](Get-Content -LiteralPath $file.FullName)
        $insertions = [System.Collections.Generic.List[object]]::new()
        $removed = Remove-OrphanedGeneratedDocumentation $lines
        for ($index = 0; $index -lt $lines.Count; $index++) {
            $line = $lines[$index].Trim()
            $match = [regex]::Match($line, '^(?:public |private |protected |internal |export |default |abstract |open |data |sealed |final |async |suspend )*(class|interface|object|enum class|type)\s+([A-Za-z_][A-Za-z0-9_]*)')
            $kind = $null
            $name = $null
            if ($match.Success) {
                $kind = $match.Groups[1].Value
                $name = $match.Groups[2].Value
            } else {
                $match = [regex]::Match($line, '^(?:public |private |protected |internal |export |default |async |suspend )*fun\s+(?:[A-Za-z_][A-Za-z0-9_]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(')
                if (-not $match.Success) { $match = [regex]::Match($line, '^(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(') }
                if (-not $match.Success) { $match = [regex]::Match($line, '^(?:export\s+)?(?:const|let)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*=>') }
                if (-not $match.Success -and $file.Extension -in @('.ts', '.tsx')) {
                    $match = [regex]::Match($line, '^\s{2}(?:public |private |protected |static |readonly |override |async )*([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*\(')
                    if ($match.Success -and $match.Groups[1].Value -in @('if', 'for', 'while', 'switch', 'catch', 'return', 'throw')) { $match = [regex]::Match('', '^$') }
                }
                if ($match.Success) {
                    $kind = 'function'
                    $name = $match.Groups[1].Value
                }
            }
            if (-not $name) { continue }

            if (Has-PrecedingDocumentation $lines $index) { continue }

            $description = Describe-Declaration $name $kind
            $indent = ([regex]::Match($lines[$index], '^\s*')).Value
            $comment = @("${indent}/**", "${indent} * $description", "${indent} */")
            $insertions.Add([pscustomobject]@{ Index = $index; Comment = $comment })
        }
        foreach ($insertion in ($insertions | Sort-Object Index -Descending)) {
            for ($commentIndex = $insertion.Comment.Count - 1; $commentIndex -ge 0; $commentIndex--) {
                $lines.Insert($insertion.Index, $insertion.Comment[$commentIndex])
            }
        }
        if ($insertions.Count -gt 0 -or $removed -gt 0) {
            [IO.File]::WriteAllLines($file.FullName, $lines, [Text.UTF8Encoding]::new($false))
            $changedFiles++
            $documentedDeclarations += $insertions.Count - $removed
        }
    }
}

Write-Host "Added inline documentation for $documentedDeclarations declarations in $changedFiles files."
