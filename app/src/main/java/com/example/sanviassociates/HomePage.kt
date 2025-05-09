package com.example.sanviassociates

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sanviassociates.DatabaseHelper.Companion.CUSTOMER_COLUMN_ADDRESS
import com.example.sanviassociates.DatabaseHelper.Companion.CUSTOMER_COLUMN_ENTRY_ID
import com.example.sanviassociates.DatabaseHelper.Companion.CUSTOMER_COLUMN_FULL_NAME
import com.example.sanviassociates.DatabaseHelper.Companion.CUSTOMER_TABLE
import com.example.sanviassociates.databinding.ActivityHomePageBinding
import com.example.sanviassociates.helpermethod.PermissionUtil
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import kotlinx.coroutines.launch
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.element.*
import com.itextpdf.layout.properties.UnitValue
import java.io.File
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.element.Image
import com.itextpdf.kernel.pdf.canvas.PdfCanvas


class HomePage : AppCompatActivity() {

    private lateinit var homepageBinding: ActivityHomePageBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: HomePageAdapter
    private var fullEntryList: List<EntryData> = listOf() // Full list of entries for search functionality

    companion object {
        private const val STORAGE_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        homepageBinding = ActivityHomePageBinding.inflate(layoutInflater)
        setContentView(homepageBinding.root)

        // Initialize database helper
        databaseHelper = DatabaseHelper(this)

        // Check permissions when the activity is created
        if (!PermissionUtil.hasNecessaryPermissions(this)) {
            PermissionUtil.requestNecessaryPermissions(this)
        }

        // Set up RecyclerView
        setupRecyclerView()

        // Add customer button
        homepageBinding.mcvAddCustomer.setOnClickListener {
            startActivity(Intent(this, AddCustomer::class.java))
        }
        homepageBinding.ivConverter.setOnClickListener {
            startActivity(Intent(this, Converter::class.java))
        }

        // Set up SearchView functionality
        setupSearchView()
    }

    override fun onResume() {
        super.onResume()
        setupRecyclerView() // Refresh RecyclerView on resume
    }

    private fun setupSearchView() {
        homepageBinding.searchView.queryHint = "Search customer by name"
        homepageBinding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    filterEntries(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let {
                    filterEntries(it)
                }
                return true
            }
        })
    }

    private fun filterEntries(query: String) {
        val filteredList = fullEntryList.filter { it.customerName.contains(query, ignoreCase = true) }
        adapter.updateData(filteredList)
    }

    private fun fetchEntriesFromDatabase(): List<EntryData> {
        val groupedData: MutableList<EntryData> = mutableListOf()
        val db = databaseHelper.readableDatabase

        val query = """
        SELECT $CUSTOMER_COLUMN_ENTRY_ID AS entry_id, 
               $CUSTOMER_COLUMN_FULL_NAME AS customerName,
               $CUSTOMER_COLUMN_ADDRESS AS address
        FROM $CUSTOMER_TABLE
        ORDER BY $CUSTOMER_COLUMN_ENTRY_ID DESC
    """
        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            do {
                val entryId = cursor.getInt(cursor.getColumnIndexOrThrow("entry_id"))
                val customerName = cursor.getString(cursor.getColumnIndexOrThrow("customerName")) ?: "Unknown"
                val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: "No details available"

                Log.d("Database", "Entry ID: $entryId, Customer Name: $customerName, Address: $address")
                groupedData.add(EntryData(entryId, customerName, address))
            } while (cursor.moveToNext())
        } else {
            Log.d("Database", "No data found in CustomerDetails table.")
        }
        cursor.close()

        return groupedData
    }

    private fun setupRecyclerView() {
        fullEntryList = fetchEntriesFromDatabase()
        adapter = HomePageAdapter(
            dataList = fullEntryList,
            onViewClick = { entryData ->
                Log.d("HomePage", "View clicked for: ${entryData.customerName}")
                Toast.makeText(this, "Generating PDF for ${entryData.customerName}", Toast.LENGTH_SHORT).show()
                val dbHelper = DatabaseHelper(this@HomePage) // Create an instance of DatabaseHelper
                generateCustomerPdf(this@HomePage, entryData.entryId, dbHelper)
            },
            onEditClick = { entryData ->
                val intent = Intent(this, UpdateCustomer::class.java)
                intent.putExtra("UNIQUE_ID", entryData.entryId)
                startActivity(intent)
            },
            onDeleteClick = { entryData ->
                deleteCustomer(entryData)
            }
        )
        homepageBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        homepageBinding.recyclerView.adapter = adapter
    }

    //Updated Version of PDF
    fun generateCustomerPdf(context: Context, entryId: Int, dbHelper: DatabaseHelper) {
        val (customerCursor, policyCursor) = dbHelper.getCustomerWithPolicies(entryId)

        if (customerCursor == null || !customerCursor.moveToFirst()) {
            Toast.makeText(context, "No customer found", Toast.LENGTH_SHORT).show()
            return
        }

        // Fetch customer details
        val fullName = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etFullName")) ?: ""
        val fatherName = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etFatherName")) ?: ""
        val motherName = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMotherName")) ?: ""
        val address = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etAddress")) ?: ""
        val birthPlace = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etBirthPlace")) ?: ""
        val birthDate = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etBirthDate")) ?: ""
        val occupation = customerCursor.getString(customerCursor.getColumnIndexOrThrow("Occuption")) ?: ""
        val annualIncome = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etAnnualIncome")) ?: ""
        val mobileNumber = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMobileNumber")) ?: ""
        val emailId = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etEmailId")) ?: ""
        val bankName = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etBanmeName")) ?: ""
        val accountNumber = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etAccountNumber")) ?: ""
        val ifsc = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etIfsc")) ?: ""
        val micr = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMicr")) ?: ""

        // Fetch family details
        val fatherAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etFatherAge")) ?: ""
        val fatherYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etFatherYear")) ?: ""
        val fatherCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etFatherCondition")) ?: ""

        val motherAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMotherAge")) ?: ""
        val motherYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMotherYear")) ?: ""
        val motherCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etMotherCondition")) ?: ""

        val brotherAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etBrotherAge")) ?: ""
        val brotherYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etBrotherYear")) ?: ""
        val brotherCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("eBrotherCondition")) ?: ""

        val sisterAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etSisterAge")) ?: ""
        val sisterYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etSisterYear")) ?: ""
        val sisterCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etSisterCondition")) ?: ""

        val husbandAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etHusbandAge")) ?: ""
        val husbandYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etHusbandYear")) ?: ""
        val husbandCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etHusbandCondition")) ?: ""

        val childrenAge = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etChildrenAge")) ?: ""
        val childrenYear = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etChildrenYear")) ?: ""
        val childrenCondition = customerCursor.getString(customerCursor.getColumnIndexOrThrow("etChildrenCondition")) ?: ""

        // Create the directory and file
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pdfFolder = File(downloadsDir, "Sanvi_Associates")
        if (!pdfFolder.exists()) pdfFolder.mkdirs()
        val pdfFile = File(pdfFolder, "${fullName}_Details.pdf")

        val pdfWriter = PdfWriter(pdfFile)
        val pdfDocument = PdfDocument(pdfWriter)
        val document = Document(pdfDocument, PageSize.A4)

        // Add Title
        document.add(Paragraph("Sanvi Associates").setTextAlignment(TextAlignment.CENTER).setBold().setFontSize(16f))
        document.add(Paragraph("Customer Details").setTextAlignment(TextAlignment.CENTER).setFontSize(14f))
        document.add(Paragraph("\n"))

        // Add Personal Details Section
        val personalDetailsTable = Table(UnitValue.createPercentArray(floatArrayOf(2f, 4f, 2f, 4f))).useAllAvailableWidth()
        personalDetailsTable.addCell(Cell(1, 4).add(Paragraph("Personal Details").setBold().setTextAlignment(TextAlignment.CENTER)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Full Name")))
        personalDetailsTable.addCell(Cell().add(Paragraph(fullName)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Father's Name")))
        personalDetailsTable.addCell(Cell().add(Paragraph(fatherName)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Mother's Name")))
        personalDetailsTable.addCell(Cell().add(Paragraph(motherName)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Address")))
        personalDetailsTable.addCell(Cell(1, 3).add(Paragraph(address)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Place of Birth")))
        personalDetailsTable.addCell(Cell().add(Paragraph(birthPlace)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Date of Birth")))
        personalDetailsTable.addCell(Cell().add(Paragraph(birthDate)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Mobile Number")))
        personalDetailsTable.addCell(Cell().add(Paragraph(mobileNumber)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Email ID")))
        personalDetailsTable.addCell(Cell().add(Paragraph(emailId)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Occupation")))
        personalDetailsTable.addCell(Cell().add(Paragraph(occupation)))
        personalDetailsTable.addCell(Cell().add(Paragraph("Annual Income")))
        personalDetailsTable.addCell(Cell().add(Paragraph(annualIncome)))
        document.add(personalDetailsTable)
        document.add(Paragraph("\n"))

        // Add Family Details Section
        val familyTable = Table(UnitValue.createPercentArray(floatArrayOf(2f, 2f, 2f, 2f))).useAllAvailableWidth()
        familyTable.addCell(Cell(1, 4).add(Paragraph("Family Details").setBold().setTextAlignment(TextAlignment.CENTER)))

        // Add headers
        familyTable.addCell(Cell().add(Paragraph("Relation").setBold().setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph("Age").setBold().setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph("Year").setBold().setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph("Condition").setBold().setTextAlignment(TextAlignment.CENTER)))

        // Add rows for family members
        familyTable.addCell(Cell().add(Paragraph("Father").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(fatherAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(fatherYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(fatherCondition).setTextAlignment(TextAlignment.CENTER)))

        familyTable.addCell(Cell().add(Paragraph("Mother").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(motherAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(motherYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(motherCondition).setTextAlignment(TextAlignment.CENTER)))

        familyTable.addCell(Cell().add(Paragraph("Brother").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(brotherAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(brotherYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(brotherCondition).setTextAlignment(TextAlignment.CENTER)))

        familyTable.addCell(Cell().add(Paragraph("Sister").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(sisterAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(sisterYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(sisterCondition).setTextAlignment(TextAlignment.CENTER)))

        familyTable.addCell(Cell().add(Paragraph("Husband/Wife").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(husbandAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(husbandYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(husbandCondition).setTextAlignment(TextAlignment.CENTER)))

        familyTable.addCell(Cell().add(Paragraph("Children").setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(childrenAge).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(childrenYear).setTextAlignment(TextAlignment.CENTER)))
        familyTable.addCell(Cell().add(Paragraph(childrenCondition).setTextAlignment(TextAlignment.CENTER)))

        document.add(familyTable)
        document.add(Paragraph("\n"))

        // Add Bank Details Section
        val bankDetailsTable = Table(UnitValue.createPercentArray(floatArrayOf(2f, 4f, 2f, 4f))).useAllAvailableWidth()
        bankDetailsTable.addCell(Cell(1, 4).add(Paragraph("Bank Details").setBold().setTextAlignment(TextAlignment.CENTER)))
        bankDetailsTable.addCell(Cell().add(Paragraph("Bank Name")))
        bankDetailsTable.addCell(Cell().add(Paragraph(bankName)))
        bankDetailsTable.addCell(Cell().add(Paragraph("Account Number")))
        bankDetailsTable.addCell(Cell().add(Paragraph(accountNumber)))
        bankDetailsTable.addCell(Cell().add(Paragraph("IFSC Code")))
        bankDetailsTable.addCell(Cell().add(Paragraph(ifsc)))
        bankDetailsTable.addCell(Cell().add(Paragraph("MICR Code")))
        bankDetailsTable.addCell(Cell().add(Paragraph(micr)))
        document.add(bankDetailsTable)

        // Finalize and close the document
        document.close()
        customerCursor.close()
        policyCursor?.close()

        Toast.makeText(context, "PDF saved to: ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun deleteCustomer(entryData: EntryData) {
        val entryId = entryData.entryId
        val customerDeleted = databaseHelper.deleteCustomerData(entryId)
        val policiesDeleted = databaseHelper.deletePolicyData(entryId)

        if (customerDeleted > 0) {
            Toast.makeText(this, "Customer and associated policies deleted successfully!", Toast.LENGTH_SHORT).show()
            setupRecyclerView()
        } else {
            Toast.makeText(this, "Failed to delete customer. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }
}