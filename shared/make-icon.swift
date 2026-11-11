// Draws the Clearpane logo: a play mark on a glass disc over an indigo-to-pink tile.
// Usage: make-icon <mode> <pixels> <out.png>
//   mode: mac    - rounded tile with margin, for the macOS app icon
//         round  - full-bleed rounded tile, for legacy Android launchers and the logo file
//         fg     - mark only on transparent, for the Android adaptive-icon foreground
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

func rgb(_ hex: Int, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: alpha)
}

if mode != "fg" {
    var rect = NSRect(x: 0, y: 0, width: s, height: s)
    var radius: CGFloat = 0
    if mode == "mac" { rect = rect.insetBy(dx: s * 0.098, dy: s * 0.098); radius = rect.width * 0.225 }
    if mode == "round" { radius = s * 0.225 }
    let tile = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
    NSGradient(colors: [rgb(0x1E1B6E), rgb(0x7C3AED), rgb(0xF0478E)],
               atLocations: [0, 0.55, 1], colorSpace: .sRGB)!.draw(in: tile, angle: -45)
    // Soft light from the top-left corner.
    NSGraphicsContext.saveGraphicsState()
    tile.addClip()
    NSGradient(starting: rgb(0xFFFFFF, 0.22), ending: rgb(0xFFFFFF, 0))!
        .draw(fromCenter: NSPoint(x: rect.minX + rect.width * 0.2, y: rect.maxY - rect.height * 0.12), radius: 0,
              toCenter: NSPoint(x: rect.minX + rect.width * 0.2, y: rect.maxY - rect.height * 0.12),
              radius: rect.width * 0.75, options: [])
    NSGraphicsContext.restoreGraphicsState()
}

if mode != "bg" {
    let fraction: CGFloat = mode == "fg" ? 0.44 : (mode == "mac" ? 0.50 : 0.60)
    let d = s * fraction
    let c = NSPoint(x: s / 2, y: s / 2)

    // Glass disc with a fine rim.
    let disc = NSBezierPath(ovalIn: NSRect(x: c.x - d / 2, y: c.y - d / 2, width: d, height: d))
    rgb(0xFFFFFF, 0.16).setFill()
    disc.fill()
    rgb(0xFFFFFF, 0.55).setStroke()
    disc.lineWidth = d * 0.035
    disc.stroke()

    // Play mark with rounded corners, nudged right so it looks centred.
    let t = d * 0.40
    let ox = c.x + t * 0.10
    let play = NSBezierPath()
    play.move(to: NSPoint(x: ox - t * 0.42, y: c.y + t * 0.52))
    play.line(to: NSPoint(x: ox - t * 0.42, y: c.y - t * 0.52))
    play.line(to: NSPoint(x: ox + t * 0.52, y: c.y))
    play.close()
    play.lineJoinStyle = .round
    play.lineWidth = t * 0.26
    NSColor.white.setFill()
    NSColor.white.setStroke()
    play.fill()
    play.stroke()
}

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[3]))
