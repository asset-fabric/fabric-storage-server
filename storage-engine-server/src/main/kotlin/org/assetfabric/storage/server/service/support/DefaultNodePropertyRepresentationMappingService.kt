/*
 * Copyright (C) 2019 Asset Fabric contributors (https://github.com/orgs/asset-fabric/teams/asset-fabric-contributors)
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.assetfabric.storage.server.service.support

import org.assetfabric.storage.BinaryReference
import org.assetfabric.storage.InputStreamWithLength
import org.assetfabric.storage.ListType
import org.assetfabric.storage.NodeReference
import org.assetfabric.storage.ParameterizedNodeReference
import org.assetfabric.storage.TypedList
import org.assetfabric.storage.rest.AbstractListNodeProperty
import org.assetfabric.storage.rest.AbstractScalarNodeProperty
import org.assetfabric.storage.rest.BinaryProperty
import org.assetfabric.storage.rest.BooleanListProperty
import org.assetfabric.storage.rest.BooleanProperty
import org.assetfabric.storage.rest.DateListProperty
import org.assetfabric.storage.rest.DateProperty
import org.assetfabric.storage.rest.DoubleListProperty
import org.assetfabric.storage.rest.DoubleProperty
import org.assetfabric.storage.rest.IntegerListProperty
import org.assetfabric.storage.rest.IntegerProperty
import org.assetfabric.storage.rest.LongListProperty
import org.assetfabric.storage.rest.LongProperty
import org.assetfabric.storage.rest.NodeProperty
import org.assetfabric.storage.rest.NodePropertyType
import org.assetfabric.storage.rest.NodeReferenceListProperty
import org.assetfabric.storage.rest.NodeReferenceProperty
import org.assetfabric.storage.rest.ParameterizedNodeReferenceListProperty
import org.assetfabric.storage.rest.ParameterizedNodeReferenceProperty
import org.assetfabric.storage.rest.StringListProperty
import org.assetfabric.storage.rest.StringProperty
import org.assetfabric.storage.server.service.BinaryManagerService
import org.assetfabric.storage.server.service.NodePropertyRepresentationMappingService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import javax.annotation.PostConstruct

@Service
class DefaultNodePropertyRepresentationMappingService: NodePropertyRepresentationMappingService {

    private lateinit var dateFormat: DateFormat

    @Value("\${assetfabric.storage.web.host}")
    private lateinit var host: String

    @Value("\${assetfabric.storage.web.port}")
    private lateinit var port: String

    @Autowired
    private lateinit var binaryManagerService: BinaryManagerService

    @PostConstruct
    private fun init() {
        val tz = TimeZone.getTimeZone("UTC")
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'") // Quoted "Z" to indicate UTC, no timezone offset
        df.timeZone = tz
        dateFormat = df
    }

    override fun getInternalPropertyRepresentation(map: Map<String, NodeProperty>, binaryMap: Map<String, InputStreamWithLength>): MutableMap<String, Any> {

        val retMap = HashMap<String, Any>()

        fun getInternalProp(np: NodeProperty): Any {
            return when(np) {
                is AbstractScalarNodeProperty -> when(np.getType()) {
                    NodePropertyType.STRING -> np.getValue()
                    NodePropertyType.INTEGER -> np.getValue().toInt()
                    NodePropertyType.LONG -> np.getValue().toLong()
                    NodePropertyType.DOUBLE -> np.getValue().toDouble()
                    NodePropertyType.BOOLEAN -> np.getValue().toBoolean()
                    NodePropertyType.DATE -> stringToDate(np.getValue())
                    NodePropertyType.BINARY -> np.getValue()
                    NodePropertyType.BINARY_INPUT -> binaryMap.getValue(np.getValue())
                    NodePropertyType.NODE -> NodeReference(np.getValue())
                    NodePropertyType.PARAMETERIZED_NODE -> {
                        val pnpp = np as ParameterizedNodeReferenceProperty
                        ParameterizedNodeReference(pnpp.getValue(), pnpp.getProperties())
                    }
                    else -> throw RuntimeException("Unknown property type ${np.getType()}")
                }
                is AbstractListNodeProperty<*> -> when (np.getType()) {
                    NodePropertyType.BOOLEAN -> TypedList(ListType.BOOLEAN, np.getValues().map { (it as String).toBoolean() })
                    NodePropertyType.DATE -> TypedList(ListType.DATE, np.getValues().map { stringToDate(it as String) })
                    NodePropertyType.INTEGER -> TypedList(ListType.INTEGER, np.getValues().map { (it as String).toInt() })
                    NodePropertyType.DOUBLE -> TypedList(ListType.DOUBLE, np.getValues().map { (it as String).toDouble() })
                    NodePropertyType.LONG -> TypedList(ListType.LONG, np.getValues().map { (it as String).toLong() })
                    NodePropertyType.STRING -> TypedList(ListType.STRING, np.getValues() as List<String>)
                    NodePropertyType.NODE -> TypedList(ListType.NODE, np.getValues().map { NodeReference(it as String) })
                    NodePropertyType.PARAMETERIZED_NODE -> {
                        val pnpp = np as ParameterizedNodeReferenceListProperty
                        val propList = pnpp.getValues()
                        val propMapList = propList.map {
                            val propMap = mutableMapOf("path" to it.getValue(), "properties" to it.getProperties())
                            propMap
                        }
                        TypedList(ListType.PARAMETERIZED_NODE, propMapList)
                    }
                    else -> throw RuntimeException("Unknown property type ${np.getType()}")
                } else ->
                    throw RuntimeException("Unknown property $np")
            }
        }

        for (prop in map) {
            retMap.put(prop.key, getInternalProp(prop.value))
        }
        return retMap
    }

    override fun getExternalPropertyRepresentation(map: Map<String, Any>): MutableMap<String, NodeProperty> {
        val retMap = HashMap<String, NodeProperty>()

        fun getExternalProp(value: Any): NodeProperty {
            return when(value) {
                is String -> StringProperty(value)
                is Int -> IntegerProperty(value)
                is Long -> LongProperty(value)
                is Double -> DoubleProperty(value)
                is Boolean -> BooleanProperty(value)
                is Date -> DateProperty(dateFormat.format(value))
                is BinaryReference -> BinaryProperty(binaryReferenceToUrl(value).path)
                is NodeReference -> NodeReferenceProperty(value.path)
                is TypedList -> {
                    when(value.listType) {
                        ListType.BOOLEAN -> {
                            val boolArray = value.values.map { (it as Boolean) }.toBooleanArray()
                            BooleanListProperty(*boolArray)
                        }
                        ListType.INTEGER -> {
                            val intArray = value.values.map { (it as Integer) }.toTypedArray()
                            IntegerListProperty(*intArray)
                        }
                        ListType.LONG -> {
                            val longArray = value.values.map { (it as Long) }.toLongArray()
                            LongListProperty(*longArray)
                        }
                        ListType.DOUBLE -> {
                            val doubleArray = value.values.map { (it as Double) }.toDoubleArray()
                            DoubleListProperty(*doubleArray)
                        }
                        ListType.DATE -> {
                            val dateArray = value.values.map { dateToString(it as Date) }.toTypedArray()
                            DateListProperty(*dateArray)
                        }
                        ListType.STRING -> {
                            val strArray = value.values.map { it as String }.toTypedArray()
                            StringListProperty(*strArray)
                        }
                        ListType.NODE -> {
                            val pathArray = value.values.map { (it as NodeReference).path }.toTypedArray()
                            NodeReferenceListProperty(*pathArray)
                        }
                        ListType.PARAMETERIZED_NODE -> {
                            val mapArray = value.values.map {
                                val inMap = it as Map<*, *>
                                val path = inMap["path"] as String
                                val props = inMap["properties"] as Map<String, Any>
                                val mappedProps = props.mapValues { getExternalProp(it) }
                                ParameterizedNodeReferenceProperty(path, mappedProps)
                            }.toTypedArray()
                            ParameterizedNodeReferenceListProperty(*mapArray)
                        }
                    }
                }
                else -> {
                    throw RuntimeException("Not implemented for type ${value::class.java}")
                }
            }
        }

        for (entry in map) {
            retMap[entry.key] = getExternalProp(entry.value)
        }

        return retMap
    }

    private fun dateToString(d: Date) = dateFormat.format(d)
    private fun stringToDate(s: String) = dateFormat.parse(s)

    private fun binaryReferenceToUrl(br: BinaryReference): BinaryReference {
        val path = br.path
        return BinaryReference("http://$host:$port/v1/binary?path=$path")
    }

}