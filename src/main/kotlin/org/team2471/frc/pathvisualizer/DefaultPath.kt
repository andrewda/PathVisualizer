package org.team2471.frc.pathvisualizer

import org.team2471.frc.lib.motion_profiling.Path2D

object DefaultPath : Path2D() {
  init {
//    addPointAndTangent(0.0, 0.0, 0.0, 6.0)
//    addPointAndTangent(-4.2, 7.0, -6.0, 3.0)
//
//    addPointAndTangent(-4.2, 7.0, 6.0, -3.0)
//    addPointAndTangent(-0.0, 0.0, -0.0, -6.0)

    addPointAndTangent(0.0,0.0,0.0,4.0)
    addPointAndTangent(0.0,13.1,4.0,2.0)
    addPointAndTangent(7.0, 13.1,2.0,0.0)

//    addPoint(0.0,0.0);
//    addPoint(0.0,13.0);
//    addPoint(7.0,13.0);

    addEasePoint(0.0, 0.0)
    addEasePoint(7.0, 1.0)
  }
}