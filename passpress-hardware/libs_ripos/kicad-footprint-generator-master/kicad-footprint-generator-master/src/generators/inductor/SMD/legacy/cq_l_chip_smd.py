import cadquery as cq


def make_chip(all_params):
    # dimensions for chip inductors
    length = all_params["length"]  # package length
    width = all_params["width"]  # package width
    height = all_params["height"]  # package height

    pin_band = all_params["pin_band"]  # pin band
    pin_thickness = all_params["pin_thickness"]  # pin thickness
    if pin_thickness == "auto":
        pin_thickness = pin_band / 10.0

    edge_fillet = all_params["edge_fillet"]  # fillet of edges
    if edge_fillet == "auto":
        edge_fillet = pin_thickness

    # Create a 3D box based on the dimension variables above and fillet it
    case = (
        cq.Workplane("XY")
        .workplane(offset=pin_thickness, centerOption="CenterOfMass")
        .box(
            length - 2 * pin_band,
            width - 2 * pin_thickness,
            height - 2 * pin_thickness,
            centered=(True, True, False),
        )
        .edges("|X")
        .fillet(edge_fillet)
    )

    # Create a 3D box based on the dimension variables above and fillet it
    pin1 = cq.Workplane("XY").box(pin_band, width, height)
    pin1 = pin1.edges("|X").fillet(edge_fillet)
    pin1 = pin1.translate((-length / 2 + pin_band / 2, 0, height / 2))
    pin2 = cq.Workplane("XY").box(pin_band, width, height)
    pin2 = pin2.edges("|X").fillet(edge_fillet)
    pin2 = pin2.translate((length / 2 - pin_band / 2, 0, height / 2))
    pins = pin1.union(pin2)
    # body_copy.ShapeColor=result.ShapeColor
    case = case.cut(pins)

    return (case, pins)
