# Minimal sfnt inspector: table list, fvar axes, OS/2 weight, name records.
# Used to confirm whether the theme font really exposes a 'wght' variation axis.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [int]$WantWeight = 500
)

$fs = [System.IO.File]::OpenRead($Path)
$br = New-Object System.IO.BinaryReader($fs)

function Rd([long]$off, [int]$n) { $fs.Position = $off; return $br.ReadBytes($n) }
function BE16($b, [int]$i) { return ($b[$i] -shl 8) -bor $b[$i + 1] }
function BE32($b, [int]$i) {
    return ([int64]$b[$i] -shl 24) -bor ($b[$i + 1] -shl 16) -bor ($b[$i + 2] -shl 8) -bor $b[$i + 3]
}
function Fixed($b, [int]$i) { return (BE32 $b $i) / 65536.0 }
function Tag($b, [int]$i) { return [System.Text.Encoding]::ASCII.GetString($b, $i, 4) }

$hdr = Rd 0 12
$scale = Tag $hdr 0
$numTables = BE16 $hdr 4
Write-Output ("sfnt={0} numTables={1} size={2}" -f $scale.Replace([char]0, '.'), $numTables, $fs.Length)

$recs = Rd 12 ($numTables * 16)
$tables = @{}
$tags = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $numTables; $i++) {
    $o = $i * 16
    $t = Tag $recs $o
    $off = BE32 $recs ($o + 8)
    $len = BE32 $recs ($o + 12)
    $tables[$t] = @($off, $len)
    $tags.Add($t)
}
Write-Output ("tables: " + (($tags | Sort-Object) -join ' '))

foreach ($t in @('fvar', 'HVAR', 'MVAR', 'AVAR', 'VVAR', 'gvar', 'cvar', 'STAT', 'CFF ', 'glyf')) {
    if ($tables.ContainsKey($t)) { Write-Output ("  present {0} len={1}" -f $t.Trim(), $tables[$t][1]) }
}

if ($tables.ContainsKey('name')) {
    $no = $tables['name'][0]
    $nb = Rd $no ([int]$tables['name'][1])
    $count = BE16 $nb 2
    $strOff = BE16 $nb 4
    $want = @(1, 2, 3, 4, 6, 16, 17)
    for ($i = 0; $i -lt $count; $i++) {
        $ro = 6 + $i * 12
        $pid = BE16 $nb $ro
        $eid = BE16 $nb ($ro + 2)
        $nameId = BE16 $nb ($ro + 6)
        $len = BE16 $nb ($ro + 8)
        $so = BE16 $nb ($ro + 10)
        if ($want -notcontains $nameId) { continue }
        if ($pid -ne 3 -and $pid -ne 0) { continue }
        $bytes = Rd ($no + $strOff + $so) $len
        $s = ''
        if ($eid -eq 0 -or $eid -eq 1 -or $eid -eq 10) {
            for ($k = 0; $k + 1 -lt $len; $k += 2) { $s += [char](($bytes[$k] -shl 8) -bor $bytes[$k + 1]) }
        } else {
            $s = [System.Text.Encoding]::ASCII.GetString($bytes)
        }
        Write-Output ("  name[{0}] = {1}" -f $nameId, $s)
    }
}

if ($tables.ContainsKey('OS/2')) {
    $o = $tables['OS/2'][0]
    $b = Rd $o 110
    Write-Output ("OS/2 usWeightClass={0} usWidthClass={1} fsSelection=0x{2:X4}" -f (BE16 $b 4), (BE16 $b 64), (BE16 $b 62))
}

if (-not $tables.ContainsKey('fvar')) {
    Write-Output "RESULT: NO fvar TABLE -> font is NOT variable; 'wght' axis requests are ignored."
    $fs.Close()
    return
}

$fo = $tables['fvar'][0]
$fb = Rd $fo ([int]$tables['fvar'][1])
$axisCount = BE16 $fb 4
$axisSize = BE16 $fb 8
$instCount = BE16 $fb 6
$nameBase = BE16 $fb 10
Write-Output ("fvar axes={0} instances={1}" -f $axisCount, $instCount)
$wght = $null
for ($i = 0; $i -lt $axisCount; $i++) {
    $ao = $nameBase + $i * $axisSize
    $tag = Tag $fb $ao
    $min = Fixed $fb ($ao + 4)
    $def = Fixed $fb ($ao + 8)
    $max = Fixed $fb ($ao + 12)
    $nid = BE16 $fb ($ao + 18)
    Write-Output ("  axis {0} min={1} default={2} max={3} nameId={4}" -f $tag, $min, $def, $max, $nid)
    if ($tag -eq 'wght') { $wght = @($min, $def, $max) }
}
if ($null -eq $wght) {
    Write-Output "RESULT: fvar exists but has NO 'wght' axis -> wght requests are ignored."
} else {
    $inRange = ($WantWeight -ge $wght[0]) -and ($WantWeight -le $wght[2])
    Write-Output ("RESULT: wght axis OK default={0} requested {1} in-range={2}" -f $wght[1], $WantWeight, $inRange)
}
$fs.Close()
