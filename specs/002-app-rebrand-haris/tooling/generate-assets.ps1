<#
.SYNOPSIS
  Generate all "Haris" brand assets from the single source logo using .NET System.Drawing.
  No ImageMagick/Inkscape required (not installed). See plan research R1-R8.

.DESCRIPTION
  Source: 1024x1024 opaque PNG (teal ribbon ring enclosing gold people mark on white bg).
  Produces:
    - Adaptive launcher foreground PNGs (mdpi..xxxhdpi), mark on transparent, safe-zone inset
    - Monochrome silhouette (themed icon, Android 13+)
    - Splash logo (mark on transparent)
    - In-app brand logo (mark on transparent)
    - Store icon 512x512 (full-bleed original)
    - Sampled dominant brand color (printed; also written to dominant-color.txt)

  Idempotent: rerunning overwrites outputs.
#>
param(
  [string]$Source = "C:\Users\Dell\Downloads\ui_system_design\logo.png",
  [string]$RepoRoot = "C:\Users\Dell\Documents\safeguard-android-NOOR"
)

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

$res     = Join-Path $RepoRoot "app\src\main\res"
$drawable= Join-Path $res "drawable"
$store   = Join-Path $RepoRoot "store"
$tooling = Join-Path $RepoRoot "specs\002-app-rebrand-haris\tooling"

# ---- Load source as 32bpp ARGB ----
$src = New-Object System.Drawing.Bitmap $Source
$W = $src.Width; $H = $src.Height
$bmp = New-Object System.Drawing.Bitmap $W, $H, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g0 = [System.Drawing.Graphics]::FromImage($bmp)
$g0.DrawImage($src, 0, 0, $W, $H)
$g0.Dispose(); $src.Dispose()

# ---- Lock bits into a byte array (BGRA) ----
$rect = New-Object System.Drawing.Rectangle 0,0,$W,$H
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $H)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)

function Get-Px([int]$x,[int]$y){ $i = $y*$stride + $x*4; return @($bytes[$i],$bytes[$i+1],$bytes[$i+2],$bytes[$i+3]) } # B,G,R,A
function Set-A([int]$x,[int]$y,[int]$a){ $i = $y*$stride + $x*4; $bytes[$i+3] = [byte]$a }

# ---- 1. Dominant color: histogram of opaque, non-white, non-black pixels, quantized to 16 ----
$hist = @{}
for($y=0; $y -lt $H; $y+=2){
  for($x=0; $x -lt $W; $x+=2){
    $i = $y*$stride + $x*4
    $b=$bytes[$i]; $gr=$bytes[$i+1]; $r=$bytes[$i+2]
    if($r -gt 235 -and $gr -gt 235 -and $b -gt 235){ continue }   # near-white
    if($r -lt 18  -and $gr -lt 18  -and $b -lt 18 ){ continue }   # near-black
    $key = "{0}_{1}_{2}" -f ([math]::Floor($r/16)), ([math]::Floor($gr/16)), ([math]::Floor($b/16))
    if($hist.ContainsKey($key)){ $hist[$key]++ } else { $hist[$key] = 1 }
  }
}
$top = $hist.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1
$parts = $top.Key -split "_"
$domR = [int]$parts[0]*16 + 8; $domG = [int]$parts[1]*16 + 8; $domB = [int]$parts[2]*16 + 8
$hex = "#{0:X2}{1:X2}{2:X2}" -f $domR,$domG,$domB
Write-Host "Dominant brand color: $hex (R=$domR G=$domG B=$domB)"
Set-Content -Path (Join-Path $tooling "dominant-color.txt") -Value $hex -Encoding ascii

# ---- 2. Background removal: flood-fill near-white from the 4 corners -> alpha 0 ----
$thr = 232
$visited = New-Object 'bool[]' ($W*$H)
$stack = New-Object System.Collections.Generic.Stack[int]
$stack.Push(0)
$stack.Push($W-1)
$stack.Push(($H-1)*$W)
$stack.Push(($H-1)*$W + ($W-1))
while($stack.Count -gt 0){
  $idx = $stack.Pop()
  if($visited[$idx]){ continue }
  $visited[$idx] = $true
  $x = $idx % $W; $y = [math]::Floor($idx / $W)
  $i = $y*$stride + $x*4
  $b=$bytes[$i]; $gr=$bytes[$i+1]; $r=$bytes[$i+2]
  if(-not ($r -ge $thr -and $gr -ge $thr -and $b -ge $thr)){ continue }  # not near-white -> boundary
  $bytes[$i+3] = 0  # make transparent
  if($x -gt 0)    { $stack.Push($idx-1) }
  if($x -lt $W-1) { $stack.Push($idx+1) }
  if($y -gt 0)    { $stack.Push($idx-$W) }
  if($y -lt $H-1) { $stack.Push($idx+$W) }
}
[System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length)
$bmp.UnlockBits($data)
# $bmp now = mark on transparent background

# ---- helper: tight bounding box of opaque pixels ----
$d2 = $bmp.LockBits($rect,[System.Drawing.Imaging.ImageLockMode]::ReadOnly,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$b2 = New-Object byte[] ($stride*$H); [System.Runtime.InteropServices.Marshal]::Copy($d2.Scan0,$b2,0,$b2.Length); $bmp.UnlockBits($d2)
$minX=$W;$minY=$H;$maxX=0;$maxY=0
for($y=0;$y -lt $H;$y++){ for($x=0;$x -lt $W;$x++){ if($b2[$y*$stride+$x*4+3] -gt 16){ if($x -lt $minX){$minX=$x}; if($x -gt $maxX){$maxX=$x}; if($y -lt $minY){$minY=$y}; if($y -gt $maxY){$maxY=$y} } } }
$markW = $maxX-$minX+1; $markH = $maxY-$minY+1
$markBmp = New-Object System.Drawing.Bitmap $markW,$markH,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$mg = [System.Drawing.Graphics]::FromImage($markBmp)
$mg.DrawImage($bmp, (New-Object System.Drawing.Rectangle 0,0,$markW,$markH), $minX,$minY,$markW,$markH,[System.Drawing.GraphicsUnit]::Pixel)
$mg.Dispose()

# ---- helper: render mark centered into a square canvas at given size + inset fraction ----
function Save-MarkCanvas([int]$size, [double]$frac, [string]$path, [System.Drawing.Bitmap]$mark){
  $canvas = New-Object System.Drawing.Bitmap $size,$size,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $cg = [System.Drawing.Graphics]::FromImage($canvas)
  $cg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $cg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $cg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $box = $size * $frac
  $scale = [math]::Min($box/$mark.Width, $box/$mark.Height)
  $dw = $mark.Width*$scale; $dh = $mark.Height*$scale
  $dx = ($size-$dw)/2; $dy = ($size-$dh)/2
  $cg.DrawImage($mark, $dx,$dy,$dw,$dh)
  $cg.Dispose()
  $canvas.Save($path,[System.Drawing.Imaging.ImageFormat]::Png)
  $canvas.Dispose()
  Write-Host "  wrote $path"
}

# ---- 3. Adaptive foreground PNGs (108dp canvas, mark in ~66% safe zone) ----
$dens = @{ "mdpi"=108; "hdpi"=162; "xhdpi"=216; "xxhdpi"=324; "xxxhdpi"=432 }
Write-Host "Foreground (safe-zone inset):"
foreach($k in $dens.Keys){
  Save-MarkCanvas $dens[$k] 0.66 (Join-Path $res "mipmap-$k\ic_launcher_foreground.png") $markBmp
}

# ---- 4. Monochrome silhouette (white alpha shape) at 432, inset ----
$mono = New-Object System.Drawing.Bitmap $markW,$markH,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$md = $mono.LockBits((New-Object System.Drawing.Rectangle 0,0,$markW,$markH),[System.Drawing.Imaging.ImageLockMode]::WriteOnly,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$mstride = $md.Stride; $mb = New-Object byte[] ($mstride*$markH)
for($y=0;$y -lt $markH;$y++){ for($x=0;$x -lt $markW;$x++){
  $sa = $b2[($y+$minY)*$stride + ($x+$minX)*4 + 3]
  $j = $y*$mstride + $x*4
  if($sa -gt 40){ $mb[$j]=255;$mb[$j+1]=255;$mb[$j+2]=255;$mb[$j+3]=255 } else { $mb[$j+3]=0 }
}}
[System.Runtime.InteropServices.Marshal]::Copy($mb,0,$md.Scan0,$mb.Length); $mono.UnlockBits($md)
Write-Host "Monochrome:"
Save-MarkCanvas 432 0.60 (Join-Path $drawable "ic_launcher_monochrome.png") $mono
$mono.Dispose()

# ---- 5. Splash logo (mark on transparent, 432) ----
Write-Host "Splash:"
Save-MarkCanvas 432 0.80 (Join-Path $drawable "splash_logo.png") $markBmp

# ---- 6. In-app brand logo (mark on transparent, 512) ----
Write-Host "In-app logo:"
Save-MarkCanvas 512 0.92 (Join-Path $drawable "ic_brand_logo.png") $markBmp

# ---- 7. Store icon 512 full-bleed (ORIGINAL with white bg) ----
$orig = New-Object System.Drawing.Bitmap $Source
$st = New-Object System.Drawing.Bitmap 512,512,([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$sg = [System.Drawing.Graphics]::FromImage($st)
$sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$sg.DrawImage($orig,0,0,512,512); $sg.Dispose()
$st.Save((Join-Path $store "ic_store_512.png"),[System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "  wrote store\ic_store_512.png"
$st.Dispose(); $orig.Dispose()

$markBmp.Dispose(); $bmp.Dispose()
Write-Host "DONE. Dominant color = $hex"
