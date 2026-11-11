// Draws the Clearpane logo. Usage: make-icon <mode> <pixels> <out.png>
//   mode: mac    - rounded tile with margin, for the macOS app icon
//         round  - full-bleed rounded tile, for legacy Android launchers and the logo file
//         fg     - glyph only on transparent, for the Android adaptive-icon foreground
//         bg     - gradient only, for the Android adaptive-icon background
import AppKit

let args = CommandLine.arguments
guard args.count == 4, let size = Int(args[2]) else {
    FileHandle.standardError.write("usage: make-icon <mac|round|fg|bg> <pixels> <out.png>\n".data(using: .utf8)!)
    exit(1)
}
let mode = args[1]
let s = CGFloat(size)

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                           bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                           colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
rep.size = NSSize(width: s, height: s)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

let teal = NSColor(srgbRed: 0.06, green: 0.73, blue: 0.69, alpha: 1)
let indigo = NSColor(srgbRed: 0.24, green: 0.22, blue: 0.80, alpha: 1)

if mode != "fg" {
    var rect = NSRect(x: 0, y: 0, width: s, height: s)
    var radius: CGFloat = 0
    if mode == "mac" { rect = rect.insetBy(dx: s * 0.098, dy: s * 0.098); radius = rect.width * 0.225 }
    if mode == "round" { radius = s * 0.22 }
    let tile = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
    NSGradient(starting: teal, ending: indigo)!.draw(in: tile, angle: -45)
}

if mode != "bg" {
    // A window pane with a tick: a clean page, checked.
    let fraction: CGFloat = mode == "fg" ? 0.40 : (mode == "mac" ? 0.44 : 0.52)
    let side = s * fraction
    let o = (s - side) / 2
    let pane = NSRect(x: o, y: o, width: side, height: side)
    let lw = side * 0.085
    NSColor.white.setStroke()

    let outline = NSBezierPath(roundedRect: pane, xRadius: side * 0.2, yRadius: side * 0.2)
    outline.lineWidth = lw
    outline.stroke()

    let bar = NSBezierPath()
    let barY = pane.maxY - side * 0.27
    bar.move(to: NSPoint(x: pane.minX, y: barY))
    bar.line(to: NSPoint(x: pane.maxX, y: barY))
    bar.lineWidth = lw
    bar.stroke()

    let tick = NSBezierPath()
    tick.move(to: NSPoint(x: pane.minX + side * 0.28, y: pane.minY + side * 0.38))
    tick.line(to: NSPoint(x: pane.minX + side * 0.44, y: pane.minY + side * 0.22))
    tick.line(to: NSPoint(x: pane.minX + side * 0.73, y: pane.minY + side * 0.54))
    tick.lineWidth = lw * 1.2
    tick.lineCapStyle = .round
    tick.lineJoinStyle = .round
    tick.stroke()
}

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[3]))
