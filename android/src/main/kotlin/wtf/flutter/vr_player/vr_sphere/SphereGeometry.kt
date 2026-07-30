package wtf.flutter.vr_player.vr_sphere

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Generates a UV sphere mesh once; reused every frame by the renderer. */
class SphereGeometry(latBands: Int = 32, lonBands: Int = 32, radius: Float = 1f) {
    val vertexBuffer: FloatBuffer
    val texCoordBuffer: FloatBuffer
    val indexBuffer: ShortBuffer
    val indexCount: Int

    init {
        val vertices = ArrayList<Float>()
        val texCoords = ArrayList<Float>()
        val indices = ArrayList<Short>()

        for (lat in 0..latBands) {
            val theta = lat * Math.PI / latBands
            val sinTheta = Math.sin(theta)
            val cosTheta = Math.cos(theta)
            for (lon in 0..lonBands) {
                val phi = lon * 2 * Math.PI / lonBands
                val sinPhi = Math.sin(phi)
                val cosPhi = Math.cos(phi)
                val x = cosPhi * sinTheta
                val y = cosTheta
                val z = sinPhi * sinTheta
                vertices.add((radius * x).toFloat())
                vertices.add((radius * y).toFloat())
                vertices.add((radius * z).toFloat())
                texCoords.add(lon.toFloat() / lonBands)
              texCoords.add(1f - lat.toFloat() / latBands)
            }
        }

        for (lat in 0 until latBands) {
            for (lon in 0 until lonBands) {
                val first = lat * (lonBands + 1) + lon
                val second = first + lonBands + 1
                indices.add(first.toShort())
                indices.add(second.toShort())
                indices.add((first + 1).toShort())
                indices.add(second.toShort())
                indices.add((second + 1).toShort())
                indices.add((first + 1).toShort())
            }
        }

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(vertices.toFloatArray()); position(0) }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(texCoords.toFloatArray()); position(0) }

        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(indices.toShortArray()); position(0) }

        indexCount = indices.size
    }
}
